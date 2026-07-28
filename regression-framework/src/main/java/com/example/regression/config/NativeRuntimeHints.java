package com.example.regression.config;

import com.example.regression.action.ActionHandler;
import com.example.regression.lifecycle.LifecycleEngine;
import com.example.regression.model.*;
import com.example.regression.report.ReportModel;
import com.example.regression.runner.TestCaseRunner;
import com.example.regression.runner.TestSuiteRunner;
import com.example.regression.runner.TestStepRunner;
import com.example.regression.verifier.Verifier;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.UUID;

public class NativeRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {

        // ── Reflection: Model records (Jackson deserialization/serialization) ──
        hints.reflection()
            .registerType(Action.class, MemberCategory.values())
            .registerType(TestCase.class, MemberCategory.values())
            .registerType(TestStep.class, MemberCategory.values())
            .registerType(Verification.class, MemberCategory.values())
            .registerType(LifecycleHook.class, MemberCategory.values())
            .registerType(TestSuite.class, MemberCategory.values())
            .registerType(TestRun.class, MemberCategory.values())
            .registerType(TestCaseResult.class, MemberCategory.values())
            .registerType(TestStepResult.class, MemberCategory.values())
            .registerType(VerificationResult.class, MemberCategory.values())
            .registerType(SuiteResult.class, MemberCategory.values());

        // ── Reflection: Report model (JSON reports + JTE template data) ──
        hints.reflection()
            .registerType(ReportModel.class, MemberCategory.values())
            .registerType(ReportModel.CaseEntry.class, MemberCategory.values())
            .registerType(ReportModel.StepEntry.class, MemberCategory.values())
            .registerType(ReportModel.VerificationEntry.class, MemberCategory.values());

        // ── Reflection: Runner inner classes (serialized by ConsoleReporter/JsonReporter) ──
        hints.reflection()
            .registerType(TestSuiteRunner.SuiteRunResult.class, MemberCategory.values())
            .registerType(TestCaseRunner.CaseExecutionResult.class, MemberCategory.values())
            .registerType(TestStepRunner.StepExecutionResult.class, MemberCategory.values());

        // ── Reflection: Lifecycle inner record ──
        hints.reflection()
            .registerType(LifecycleEngine.HookResult.class, MemberCategory.values());

        // ── Reflection: Configuration properties (Spring binding via reflection) ──
        hints.reflection()
            .registerType(AppConfig.class, MemberCategory.values())
            .registerType(AppConfig.Aws.class, MemberCategory.values());

        // ── Reflection: Jackson infrastructure ──
        hints.reflection()
            .registerType(LinkedHashMap.class, MemberCategory.values())
            .registerType(UUID.class, MemberCategory.values())
            .registerType(ResultSet.class, MemberCategory.values());

        // ── Resources: Flyway migrations ──
        hints.resources()
            .registerPattern("db/migration/V1__init_schema.sql")
            .registerPattern("db/migration/V2__retention_procedure.sql");

        // ── Resources: JTE templates ──
        hints.resources()
            .registerPattern("jte/report.jte");

        // ── Resources: Spring Boot config ──
        hints.resources()
            .registerPattern("application.yml");

        // ── Resources: Native image metadata ──
        hints.resources()
            .registerPattern("META-INF/native-image/com.example.regression/reflect-config.json")
            .registerPattern("META-INF/native-image/com.example.regression/resource-config.json")
            .registerPattern("META-INF/native-image/com.example.regression/proxy-config.json");

        // ── Proxies: ActionHandler and Verifier interfaces ──
        hints.proxies()
            .registerJdkProxy(ActionHandler.class)
            .registerJdkProxy(Verifier.class);
    }
}

package com.example.regression.runner;

import com.example.regression.action.ActionHandler;
import com.example.regression.action.ActionRegistry;
import com.example.regression.config.AppConfig;
import com.example.regression.context.ContextResolver;
import com.example.regression.context.SuiteContext;
import com.example.regression.context.TestCaseContext;
import com.example.regression.context.TestStepContext;
import com.example.regression.lifecycle.LifecycleEngine;
import com.example.regression.model.*;
import com.example.regression.verifier.ResponseVerifier;
import com.example.regression.verifier.VerifierRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestStepRunnerTest {

    private TestStepRunner runner;

    @BeforeEach
    void setUp() {
        var resolver = new ContextResolver();
        var actionRegistry = new ActionRegistry(List.of(new ActionHandler() {
            @Override
            public String supportedActionType() { return "test:ok"; }
            @Override
            public Map<String, Object> handle(Action action, TestStepContext context, ContextResolver r) {
                return Map.of("statusCode", 200, "result", "ok");
            }
        }));
        var verifierRegistry = new VerifierRegistry(List.of(new ResponseVerifier()));
        var lifecycleEngine = new LifecycleEngine(actionRegistry, resolver);
        runner = new TestStepRunner(actionRegistry, verifierRegistry, lifecycleEngine, resolver);
    }

    @Test
    void successfulStepExecution() {
        var suite = new TestSuite("test", 1, List.of(), null, null, null, null);
        var config = new AppConfig();
        var suiteCtx = new SuiteContext(suite, config);
        var testCase = new TestCase("test-case", List.of(), null, null, null, null, null);
        var caseCtx = new TestCaseContext(suiteCtx, testCase, Map.of());
        var step = new TestStep("step1",
                new Action("test:ok", Map.of()),
                List.of(new Verification("jsonPath", Map.of("path", "$.statusCode", "value", 200))),
                null, null, 0, null, null, null);

        var result = runner.executeStep(step, caseCtx);
        assertEquals("PASSED", result.status());
        assertEquals(200, result.response().get("statusCode"));
    }

    @Test
    void retryOnFailure() {
        var suite = new TestSuite("test", 1, List.of(), null, null, null, null);
        var config = new AppConfig();
        var suiteCtx = new SuiteContext(suite, config);
        var testCase = new TestCase("test-case", List.of(), null, null, null, null, null);
        var caseCtx = new TestCaseContext(suiteCtx, testCase, Map.of());
        var step = new TestStep("step1",
                new Action("nonexistent:action", Map.of()),
                null, null, null, 1, null, null, null);

        var result = runner.executeStep(step, caseCtx);
        assertEquals("FAILED", result.status());
        assertTrue(result.retryCount() > 0 || result.retryCount() == 1);
    }
}

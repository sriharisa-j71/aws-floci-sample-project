package com.example.regression.runner;

import com.example.regression.action.ActionHandler;
import com.example.regression.action.ActionRegistry;
import com.example.regression.config.AppConfig;
import com.example.regression.context.ContextResolver;
import com.example.regression.context.TestStepContext;
import com.example.regression.db.*;
import com.example.regression.lifecycle.LifecycleEngine;
import com.example.regression.model.Action;
import com.example.regression.verifier.VerifierRegistry;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestSuiteRunnerTest {

    @Test
    void suiteNotFound() {
        var config = new AppConfig();
        config.setSuiteDirs(List.of("/nonexistent"));
        var jsonMapper = new JsonMapper();

        var actionHandler = new ActionHandler() {
            @Override public String supportedActionType() { return "test:action"; }
            @Override public Map<String, Object> handle(Action action, TestStepContext context, ContextResolver r) {
                return Map.of("statusCode", 200);
            }
        };
        var actionRegistry = new ActionRegistry(List.of(actionHandler));
        var verifierRegistry = new VerifierRegistry(List.of());
        var resolver = new ContextResolver();
        var lifecycleEngine = new LifecycleEngine(actionRegistry, resolver);
        var caseRunner = new TestCaseRunner(new TestStepRunner(actionRegistry, verifierRegistry, lifecycleEngine, resolver), lifecycleEngine);

        var runner = new TestSuiteRunner(config, caseRunner, lifecycleEngine, jsonMapper,
                new TestRunRepository(null, jsonMapper),
                new SuiteResultRepository(null),
                new TestCaseResultRepository(null, jsonMapper),
                new TestStepResultRepository(null, jsonMapper),
                new VerificationResultRepository(null, jsonMapper));

        assertThrows(RuntimeException.class, () -> runner.executeSuite("nonexistent", null));
    }
}

package com.example.regression.lifecycle;

import com.example.regression.action.ActionRegistry;
import com.example.regression.config.AppConfig;
import com.example.regression.context.ContextResolver;
import com.example.regression.context.SuiteContext;
import com.example.regression.model.Action;
import com.example.regression.model.LifecycleHook;
import com.example.regression.model.TestCase;
import com.example.regression.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleEngineTest {

    private LifecycleEngine engine;

    @BeforeEach
    void setUp() {
        var resolver = new ContextResolver();
        engine = new LifecycleEngine(new ActionRegistry(List.of()), resolver);
    }

    @Test
    void emptyHooks() {
        var suite = new TestSuite("test", 1, List.of(), null, null, null, null);
        var config = new AppConfig();
        var suiteCtx = new SuiteContext(suite, config);
        var results = engine.executeHooks(List.of(), suiteCtx, null);
        assertTrue(results.isEmpty());
    }

    @Test
    void hooksExecutedInOrder() {
        var suite = new TestSuite("test", 1, List.of(), null, null, null, null);
        var config = new AppConfig();
        var suiteCtx = new SuiteContext(suite, config);
        var hooks = List.of(
                new LifecycleHook("beforeAll", List.of(), "second", false, 1),
                new LifecycleHook("beforeAll", List.of(), "first", false, 0)
        );
        var results = engine.executeHooks(hooks, suiteCtx, null);
        assertEquals(2, results.size());
    }

    @Test
    void stopOnFailureHalts() {
        var suite = new TestSuite("test", 1, List.of(), null, null, null, null);
        var config = new AppConfig();
        var suiteCtx = new SuiteContext(suite, config);
        var hooks = List.of(
                new LifecycleHook("beforeAll", List.of(
                        new Action("nonexistent:action", Map.of())
                ), "failing", true, 0),
                new LifecycleHook("beforeAll", List.of(
                        new Action("nonexistent:action", Map.of())
                ), "should-not-run", false, 1)
        );
        var results = engine.executeHooks(hooks, suiteCtx, null);
        assertEquals(1, results.size());
        assertFalse(results.getFirst().passed());
    }

    @Test
    void hookFailureDoesNotStopSuiteByDefault() {
        var suite = new TestSuite("test", 1, List.of(), null, null, null, null);
        var config = new AppConfig();
        var suiteCtx = new SuiteContext(suite, config);
        var hooks = List.of(
                new LifecycleHook("beforeAll", List.of(
                        new Action("nonexistent:action", Map.of())
                ), "failing", false, 0),
                new LifecycleHook("beforeAll", List.of(
                        new Action("nonexistent:action", Map.of())
                ), "also-failing", false, 1)
        );
        var results = engine.executeHooks(hooks, suiteCtx, null);
        assertEquals(2, results.size());
    }
}

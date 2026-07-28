package com.example.regression.lifecycle;

import com.example.regression.action.ActionRegistry;
import com.example.regression.context.ContextResolver;
import com.example.regression.context.SuiteContext;
import com.example.regression.context.TestCaseContext;
import com.example.regression.context.TestStepContext;
import com.example.regression.model.Action;
import com.example.regression.model.LifecycleHook;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class LifecycleEngine {

    private final ActionRegistry actionRegistry;
    private final ContextResolver contextResolver;

    public LifecycleEngine(ActionRegistry actionRegistry, ContextResolver contextResolver) {
        this.actionRegistry = actionRegistry;
        this.contextResolver = contextResolver;
    }

    public List<HookResult> executeHooks(List<LifecycleHook> hooks, SuiteContext suiteCtx, TestCaseContext caseCtx) {
        if (hooks == null || hooks.isEmpty()) return List.of();

        var sorted = hooks.stream()
                .sorted(Comparator.comparingInt(h -> h.order() != null ? h.order() : 0))
                .collect(Collectors.toList());

        var results = new ArrayList<HookResult>();
        for (var hook : sorted) {
            var result = executeHook(hook, suiteCtx, caseCtx);
            results.add(result);
            if (!result.passed() && Boolean.TRUE.equals(hook.stopOnFailure())) {
                break;
            }
        }
        return results;
    }

    private HookResult executeHook(LifecycleHook hook, SuiteContext suiteCtx, TestCaseContext caseCtx) {
        var errors = new ArrayList<String>();
        for (Action action : hook.actions()) {
            try {
                var handler = actionRegistry.getHandler(action.actionType());
                var stepCtx = createDummyStepContext(caseCtx);
                var resolvedParams = contextResolver.resolve(action.params(), stepCtx, "hook");
                var resolvedAction = new Action(action.actionType(), asStringMap(resolvedParams));
                var response = handler.handle(resolvedAction, stepCtx, contextResolver);

                if ("beforeAll".equals(hook.hookType()) || "beforeEach".equals(hook.hookType())) {
                    if (response != null) {
                        suiteCtx.sharedData().putAll(response);
                    }
                }
            } catch (Exception e) {
                errors.add("%s: %s".formatted(action.actionType(), e.getMessage()));
            }
        }
        return new HookResult(hook.hookType(), errors.isEmpty(), errors);
    }

    private TestStepContext createDummyStepContext(TestCaseContext caseCtx) {
        return new TestStepContext(caseCtx, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asStringMap(Object resolved) {
        if (resolved instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    public record HookResult(String hookType, boolean passed, List<String> errors) {}
}

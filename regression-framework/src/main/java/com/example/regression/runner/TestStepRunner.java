package com.example.regression.runner;

import com.example.regression.action.ActionRegistry;
import com.example.regression.context.ContextResolver;
import com.example.regression.context.TestCaseContext;
import com.example.regression.context.TestStepContext;
import com.example.regression.exception.ActionExecutionException;
import com.example.regression.lifecycle.LifecycleEngine;
import com.example.regression.model.*;
import com.example.regression.verifier.VerifierRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Component
public class TestStepRunner {

    private final ActionRegistry actionRegistry;
    private final VerifierRegistry verifierRegistry;
    private final LifecycleEngine lifecycleEngine;
    private final ContextResolver contextResolver;

    public TestStepRunner(ActionRegistry actionRegistry,
                          VerifierRegistry verifierRegistry,
                          LifecycleEngine lifecycleEngine,
                          ContextResolver contextResolver) {
        this.actionRegistry = actionRegistry;
        this.verifierRegistry = verifierRegistry;
        this.lifecycleEngine = lifecycleEngine;
        this.contextResolver = contextResolver;
    }

    public static class StepExecutionResult {
        private final String status;
        private final String failureCategory;
        private final String actionType;
        private final Map<String, Object> actionParams;
        private final Map<String, Object> response;
        private final long durationMs;
        private final int retryCount;
        private final String errorMessage;
        private final List<com.example.regression.model.VerificationResult> verificationResults;

        public StepExecutionResult(String status, String failureCategory, String actionType,
                                    Map<String, Object> actionParams, Map<String, Object> response,
                                    long durationMs, int retryCount, String errorMessage,
                                    List<com.example.regression.model.VerificationResult> verificationResults) {
            this.status = status;
            this.failureCategory = failureCategory;
            this.actionType = actionType;
            this.actionParams = actionParams;
            this.response = response;
            this.durationMs = durationMs;
            this.retryCount = retryCount;
            this.errorMessage = errorMessage;
            this.verificationResults = verificationResults;
        }

        public String status() { return status; }
        public String failureCategory() { return failureCategory; }
        public String actionType() { return actionType; }
        public Map<String, Object> actionParams() { return actionParams; }
        public Map<String, Object> response() { return response; }
        public long durationMs() { return durationMs; }
        public int retryCount() { return retryCount; }
        public String errorMessage() { return errorMessage; }
        public List<com.example.regression.model.VerificationResult> verificationResults() { return verificationResults; }
    }

    public StepExecutionResult executeStep(TestStep step, TestCaseContext caseCtx) {
        int maxRetries = step.retryCount() != null ? step.retryCount() : 0;
        List<com.example.regression.model.VerificationResult> allVerifications = new ArrayList<>();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            var stepCtx = new TestStepContext(caseCtx, step);
            contextResolver.clearCache();
            var startTime = Instant.now();

            try {
                lifecycleEngine.executeHooks(step.before(), caseCtx.parent(), caseCtx);

                var handler = actionRegistry.getHandler(step.action().actionType());
                var resolvedParams = contextResolver.resolve(step.action().params(), stepCtx, "action");
                var resolvedAction = new Action(step.action().actionType(), asStringMap(resolvedParams));
                var response = handler.handle(resolvedAction, stepCtx, contextResolver);
                stepCtx.response(response);

                lifecycleEngine.executeHooks(step.after(), caseCtx.parent(), caseCtx);

                if (step.verifications() != null) {
                    for (var verification : step.verifications()) {
                        var verifier = verifierRegistry.getVerifier(verification.verifierType());
                        var actual = response != null ? response : Map.<String, Object>of();
                        var vResult = verifier.verify(actual, verification.expected(), stepCtx);
                        allVerifications.add(vResult);
                        stepCtx.verificationResults().add(vResult);
                    }
                }

                var durationMs = Duration.between(startTime, Instant.now()).toMillis();
                caseCtx.stepResponses().put(step.stepName(), response);

                boolean hasFailures = allVerifications.stream().anyMatch(v -> !v.passed());
                String finalStatus = hasFailures ? "FAILED" : "PASSED";
                String failureCategory = null;
                if (hasFailures) {
                    failureCategory = attempt > 0 ? "FLAKY" : "ASSERTION";
                }

                return new StepExecutionResult(finalStatus, failureCategory,
                        step.action().actionType(), step.action().params(), response,
                        durationMs, attempt, null, allVerifications);

            } catch (Exception e) {
                var durationMs = Duration.between(startTime, Instant.now()).toMillis();
                if (attempt < maxRetries) {
                    continue;
                }
                return new StepExecutionResult("FAILED",
                        e instanceof TimeoutException ? "TIMEOUT" : "ACTION_ERROR",
                        step.action().actionType(), step.action().params(), null,
                        durationMs, attempt, e.getMessage(), allVerifications);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asStringMap(Object resolved) {
        if (resolved instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }
}

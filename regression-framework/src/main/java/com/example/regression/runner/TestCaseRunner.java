package com.example.regression.runner;

import com.example.regression.context.SuiteContext;
import com.example.regression.context.TestCaseContext;
import com.example.regression.runner.TestStepRunner.StepExecutionResult;
import com.example.regression.lifecycle.LifecycleEngine;
import com.example.regression.model.TestCase;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
public class TestCaseRunner {

    private final TestStepRunner stepRunner;
    private final LifecycleEngine lifecycleEngine;

    public TestCaseRunner(TestStepRunner stepRunner, LifecycleEngine lifecycleEngine) {
        this.stepRunner = stepRunner;
        this.lifecycleEngine = lifecycleEngine;
    }

    public static class CaseExecutionResult {
        private final String caseName;
        private final String status;
        private final Map<String, Object> dataRow;
        private final long durationMs;
        private final int totalSteps;
        private final int passedSteps;
        private final int failedSteps;
        private final String errorMessage;
        private final Map<String, StepExecutionResult> stepResults;

        public CaseExecutionResult(String caseName, String status, Map<String, Object> dataRow,
                                    long durationMs, int totalSteps, int passedSteps, int failedSteps,
                                    String errorMessage, Map<String, StepExecutionResult> stepResults) {
            this.caseName = caseName;
            this.status = status;
            this.dataRow = dataRow;
            this.durationMs = durationMs;
            this.totalSteps = totalSteps;
            this.passedSteps = passedSteps;
            this.failedSteps = failedSteps;
            this.errorMessage = errorMessage;
            this.stepResults = stepResults;
        }

        public String caseName() { return caseName; }
        public String status() { return status; }
        public Map<String, Object> dataRow() { return dataRow; }
        public long durationMs() { return durationMs; }
        public int totalSteps() { return totalSteps; }
        public int passedSteps() { return passedSteps; }
        public int failedSteps() { return failedSteps; }
        public String errorMessage() { return errorMessage; }
        public Map<String, StepExecutionResult> stepResults() { return stepResults; }
    }

    public CaseExecutionResult executeCase(TestCase testCase, Map<String, Object> dataRow, SuiteContext suiteCtx) {
        var caseCtx = new TestCaseContext(suiteCtx, testCase, dataRow);
        var startTime = Instant.now();

        try {
            lifecycleEngine.executeHooks(testCase.beforeEach(), suiteCtx, caseCtx);

            Map<String, StepExecutionResult> stepResults = new LinkedHashMap<>();
            int passedCount = 0;
            int failedCount = 0;

            for (var step : testCase.steps()) {
                var result = stepRunner.executeStep(step, caseCtx);
                stepResults.put(step.stepName(), result);
                if ("PASSED".equals(result.status())) {
                    passedCount++;
                } else {
                    failedCount++;
                }
            }

            lifecycleEngine.executeHooks(testCase.afterEach(), suiteCtx, caseCtx);

            var durationMs = Duration.between(startTime, Instant.now()).toMillis();
            String status = failedCount > 0 ? "FAILED" : "PASSED";

            return new CaseExecutionResult(testCase.caseName(), status, dataRow,
                    durationMs, testCase.steps().size(), passedCount, failedCount,
                    null, stepResults);

        } catch (Exception e) {
            var durationMs = Duration.between(startTime, Instant.now()).toMillis();
            return new CaseExecutionResult(testCase.caseName(), "ERROR", dataRow,
                    durationMs, testCase.steps().size(), 0, testCase.steps().size(),
                    e.getMessage(), Map.of());
        }
    }
}

package com.example.regression.report;

import com.example.regression.runner.TestSuiteRunner;
import org.springframework.stereotype.Component;

@Component
public class ConsoleReporter {

    public String renderSummary(TestSuiteRunner.SuiteRunResult result) {
        var sb = new StringBuilder();
        sb.append(String.format("Suite: %s  |  %d/%d cases  |  %d passed  %d failed  %d error  %d skipped  |  %dms",
                result.suiteName(), result.totalCases(), result.totalCases(),
                result.passedCases(), result.failedCases(), result.errorCases(),
                result.skippedCases(), result.durationMs()));
        sb.append("\n");

        long assertions = 0, timeouts = 0, actionErrors = 0;
        for (var caseResult : result.caseResults()) {
            for (var stepResult : caseResult.stepResults().values()) {
                if ("ASSERTION".equals(stepResult.failureCategory())) assertions++;
                else if ("TIMEOUT".equals(stepResult.failureCategory())) timeouts++;
                else if ("ACTION_ERROR".equals(stepResult.failureCategory())) actionErrors++;
            }
        }

        if (assertions > 0 || timeouts > 0 || actionErrors > 0) {
            sb.append("Failure breakdown: ");
            if (assertions > 0) sb.append(assertions).append(" ASSERTION, ");
            if (timeouts > 0) sb.append(timeouts).append(" TIMEOUT, ");
            if (actionErrors > 0) sb.append(actionErrors).append(" ACTION_ERROR, ");
            sb.delete(sb.length() - 2, sb.length());
            sb.append("\n");
        }

        sb.append("\nExit code: ").append("PASSED".equals(result.status()) ? 0 : 1).append("\n");
        return sb.toString();
    }

    public String renderSummaryFromModel(ReportModel model) {
        var sb = new StringBuilder();
        var run = model.run();
        sb.append(String.format("Suite: %s  |  %s  |  %dms",
                run.suiteName(), run.status(), run.durationMs()));
        sb.append("\n");

        for (var entry : model.cases()) {
            var cr = entry.result();
            sb.append("  ").append(cr.caseName()).append(" (").append(cr.status())
                    .append(") [").append(cr.durationMs()).append("ms]\n");
            for (var step : entry.steps()) {
                var sr = step.result();
                sb.append("    \u2514\u2500\u2500 ").append(sr.stepName()).append(" \u2192 ")
                        .append(sr.status()).append(" [").append(sr.durationMs()).append("ms]\n");
            }
        }
        return sb.toString();
    }
}

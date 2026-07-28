package com.example.regression.shell;

import com.example.regression.db.TestCaseResultRepository;
import com.example.regression.db.TestRunRepository;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.util.UUID;
import java.util.stream.Collectors;

@ShellComponent
public class CompareCommand {

    private final TestRunRepository testRunRepository;
    private final TestCaseResultRepository caseResultRepository;

    public CompareCommand(TestRunRepository testRunRepository,
                           TestCaseResultRepository caseResultRepository) {
        this.testRunRepository = testRunRepository;
        this.caseResultRepository = caseResultRepository;
    }

    @ShellMethod(key = "compare", value = "Compare two test runs")
    public String compare(
            @ShellOption(value = "--run-a", help = "Run ID A") String runIdA,
            @ShellOption(value = "--run-b", help = "Run ID B") String runIdB) {
        var runA = testRunRepository.findById(UUID.fromString(runIdA));
        var runB = testRunRepository.findById(UUID.fromString(runIdB));

        if (!runA.suiteVersion().equals(runB.suiteVersion())) {
            return "Warning: suite versions differ (" + runA.suiteVersion() + " vs " + runB.suiteVersion() + ")";
        }

        var casesA = caseResultRepository.findByRunId(runA.runId());
        var casesB = caseResultRepository.findByRunId(runB.runId());

        var mapA = casesA.stream().collect(Collectors.toMap(c -> c.caseName(), c -> c));
        var mapB = casesB.stream().collect(Collectors.toMap(c -> c.caseName(), c -> c));

        var sb = new StringBuilder();
        sb.append("Comparing ").append(runA.status()).append(" vs ").append(runB.status())
                .append(" \u2014 suite_version: ").append(runA.suiteVersion()).append("\n\n");

        int regressions = 0;
        int improvements = 0;

        for (var entry : mapA.entrySet()) {
            var name = entry.getKey();
            var a = entry.getValue();
            var b = mapB.get(name);
            if (b == null) continue;

            if ("PASSED".equals(a.status()) && !"PASSED".equals(b.status())) {
                sb.append("Regression:  ").append(name).append(":  ")
                        .append(a.status()).append(" \u2192 ").append(b.status());
                if (a.durationMs() != null && b.durationMs() != null) {
                    var diff = b.durationMs() - a.durationMs();
                    sb.append("  (duration: ").append(diff > 0 ? "+" : "").append(diff).append("ms)");
                }
                sb.append("\n");
                regressions++;
            } else if (!"PASSED".equals(a.status()) && "PASSED".equals(b.status())) {
                sb.append("Improvement: ").append(name).append(":  ")
                        .append(a.status()).append(" \u2192 ").append(b.status());
                if (a.durationMs() != null && b.durationMs() != null) {
                    var diff = a.durationMs() - b.durationMs();
                    sb.append("  (duration: -").append(diff).append("ms)");
                }
                sb.append("\n");
                improvements++;
            }
        }

        sb.append("\nRegressions: ").append(regressions).append(", Improvements: ").append(improvements).append("\n");
        sb.append("Duration: ").append(runA.durationMs()).append("ms \u2192 ")
                .append(runB.durationMs()).append("ms");

        if (runA.durationMs() != null && runB.durationMs() != null) {
            var totalDiff = runB.durationMs() - runA.durationMs();
            var pct = runA.durationMs() > 0 ? (totalDiff * 100 / runA.durationMs()) : 0;
            sb.append(" (").append(totalDiff > 0 ? "+" : "").append(totalDiff).append("ms, ")
                    .append(pct > 0 ? "+" : "").append(pct).append("%)");
        }

        sb.append("\nStatus:   ").append(runA.status()).append(" \u2192 ").append(runB.status()).append("\n");
        return sb.toString();
    }
}

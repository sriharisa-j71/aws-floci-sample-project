package com.example.regression.shell;

import com.example.regression.db.TestRunRepository;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

@ShellComponent
public class HistoryCommand {

    private final TestRunRepository testRunRepository;

    public HistoryCommand(TestRunRepository testRunRepository) {
        this.testRunRepository = testRunRepository;
    }

    @ShellMethod(key = "run-history", value = "Show run history for a suite")
    public String history(
            @ShellOption(value = "--suite", help = "Suite name") String suite,
            @ShellOption(value = "--limit", defaultValue = "15") int limit,
            @ShellOption(value = "--days", defaultValue = "30") int days) {
        var runs = testRunRepository.listBySuite(suite, limit);
        if (runs.isEmpty()) return "No runs found for suite: " + suite;

        var sb = new StringBuilder();
        sb.append(suite).append(" — last ").append(runs.size()).append(" runs (").append(days).append(" days)\n");

        var passed = runs.stream().filter(r -> "PASSED".equals(r.status())).count();
        var total = runs.size();
        var passRate = total > 0 ? (passed * 100 / total) : 0;

        var sparkline = new StringBuilder();
        for (var run : runs.reversed()) {
            sparkline.append(switch (run.status()) {
                case "PASSED" -> "\u2587";
                case "FAILED" -> "\u2583";
                default -> "\u2581";
            });
        }

        sb.append("Pass rate:  ").append(sparkline)
                .append("  ").append(passRate).append("% (").append(passed).append("/").append(total).append(")\n");

        var durations = runs.stream()
                .filter(r -> r.durationMs() != null)
                .mapToLong(r -> r.durationMs())
                .sorted()
                .toArray();

        if (durations.length > 0) {
            var median = durations[durations.length / 2];
            var sparkDurations = new StringBuilder();
            var maxDur = durations[durations.length - 1];
            for (var run : runs.reversed()) {
                var d = run.durationMs() != null ? run.durationMs() : 0L;
                var bar = maxDur > 0 ? (d * 7 / maxDur) : 0;
                sparkDurations.append((char) ('\u2581' + Math.min(bar, 7)));
            }
            sb.append("Duration:   ").append(sparkDurations)
                    .append("  median ").append(median).append("ms\n");
        }

        return sb.toString();
    }
}

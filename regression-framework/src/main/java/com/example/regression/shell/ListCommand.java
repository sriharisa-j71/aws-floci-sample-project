package com.example.regression.shell;

import com.example.regression.config.AppConfig;
import com.example.regression.db.TestRunRepository;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.io.File;

@ShellComponent
public class ListCommand {

    private final TestRunRepository testRunRepository;
    private final AppConfig appConfig;

    public ListCommand(TestRunRepository testRunRepository, AppConfig appConfig) {
        this.testRunRepository = testRunRepository;
        this.appConfig = appConfig;
    }

    @ShellMethod(key = "list-runs", value = "List recent test runs")
    public String listRuns(
            @ShellOption(value = "--limit", defaultValue = "10") int limit,
            @ShellOption(value = "--suite", defaultValue = ShellOption.NULL) String suite) {
        var runs = suite != null
                ? testRunRepository.listBySuite(suite, limit)
                : testRunRepository.listRecent(limit);

        var sb = new StringBuilder();
        sb.append(String.format("%-36s %-20s %-10s %-20s %-10s%n",
                "Run ID", "Suite", "Status", "Start Time", "Duration"));
        sb.append("-".repeat(98)).append("\n");
        for (var run : runs) {
            sb.append(String.format("%-36s %-20s %-10s %-20s %-10s%n",
                    run.runId(), run.suiteName(), run.status(),
                    run.startTime().toString().substring(0, 19),
                    run.durationMs() != null ? run.durationMs() + "ms" : "-"));
        }
        return sb.toString();
    }

    @ShellMethod(key = "list-suites", value = "List available test suites")
    public String listSuites() {
        var sb = new StringBuilder("Available suites:\n");
        for (var dir : appConfig.getSuiteDirs()) {
            var f = new File(dir);
            if (f.exists() && f.isDirectory()) {
                var files = f.listFiles((d, name) -> name.endsWith(".json"));
                if (files != null) {
                    for (var file : files) {
                        sb.append("  - ").append(file.getName().replace(".json", "")).append("\n");
                    }
                }
            }
        }
        return sb.toString();
    }
}

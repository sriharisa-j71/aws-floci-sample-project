package com.example.regression.shell;

import com.example.regression.db.TestRunRepository;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.time.Instant;
import java.util.UUID;

@ShellComponent
public class RecoverCommand {

    private final TestRunRepository testRunRepository;

    public RecoverCommand(TestRunRepository testRunRepository) {
        this.testRunRepository = testRunRepository;
    }

    @ShellMethod(key = "recover", value = "Detect and recover stale RUNNING runs")
    public String recover(
            @ShellOption(value = "--run-id", defaultValue = ShellOption.NULL) String runId) {
        if (runId != null) {
            var uuid = UUID.fromString(runId);
            var run = testRunRepository.findById(uuid);
            if (run == null) return "Run not found: " + runId;
            testRunRepository.updateStatus(uuid, "ERROR", Instant.now(), run.durationMs() != null ? run.durationMs() : 0L,
                    run.totalCases(), run.passedCases(), run.failedCases(), run.errorCases(), run.skippedCases());
            return "Recovered run " + runId + " (marked as ERROR)";
        }

        var staleRuns = testRunRepository.findStaleRuns();
        if (staleRuns.isEmpty()) return "No stale runs found.";

        var sb = new StringBuilder();
        sb.append("Found ").append(staleRuns.size()).append(" stale run(s):\n");
        for (var run : staleRuns) {
            sb.append("  - ").append(run.runId()).append(" (").append(run.suiteName()).append(")\n");
            testRunRepository.updateStatus(run.runId(), "ERROR", Instant.now(),
                    run.durationMs() != null ? run.durationMs() : 0L,
                    run.totalCases(), run.passedCases(), run.failedCases(), run.errorCases(), run.skippedCases());
        }
        sb.append("All stale runs marked as ERROR.");
        return sb.toString();
    }
}

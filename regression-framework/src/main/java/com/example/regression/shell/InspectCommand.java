package com.example.regression.shell;

import com.example.regression.db.TestCaseResultRepository;
import com.example.regression.db.TestRunRepository;
import com.example.regression.db.TestStepResultRepository;
import com.example.regression.db.VerificationResultRepository;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.util.UUID;

@ShellComponent
public class InspectCommand {

    private final TestRunRepository testRunRepository;
    private final TestCaseResultRepository caseResultRepository;
    private final TestStepResultRepository stepResultRepository;
    private final VerificationResultRepository verificationResultRepository;

    public InspectCommand(TestRunRepository testRunRepository,
                           TestCaseResultRepository caseResultRepository,
                           TestStepResultRepository stepResultRepository,
                           VerificationResultRepository verificationResultRepository) {
        this.testRunRepository = testRunRepository;
        this.caseResultRepository = caseResultRepository;
        this.stepResultRepository = stepResultRepository;
        this.verificationResultRepository = verificationResultRepository;
    }

    @ShellMethod(key = "inspect", value = "Inspect run details as formatted tree")
    public String inspect(
            @ShellOption(value = "--run-id", help = "Run ID") String runId,
            @ShellOption(value = "--case", defaultValue = ShellOption.NULL) String caseName,
            @ShellOption(value = "--step", defaultValue = ShellOption.NULL) String stepName) {
        var uuid = UUID.fromString(runId);
        var run = testRunRepository.findById(uuid);
        var sb = new StringBuilder();
        sb.append(run.suiteName()).append(" (").append(run.status()).append(")")
                .append(" [").append(run.durationMs()).append("ms]\n");

        var cases = caseResultRepository.findByRunId(uuid);
        for (var c : cases) {
            if (caseName != null && !c.caseName().equals(caseName)) continue;
            sb.append("\u251C\u2500\u2500 ").append(c.caseName())
                    .append(" (").append(c.status()).append(")")
                    .append(" [").append(c.durationMs()).append("ms]\n");

            var steps = stepResultRepository.findByCaseResultId(c.id());
            for (var s : steps) {
                if (stepName != null && !s.stepName().equals(stepName)) continue;
                sb.append("\u2502   \u251C\u2500\u2500 ").append(s.stepName())
                        .append(" \u2192 ").append(s.status())
                        .append(" [").append(s.durationMs()).append("ms]\n");
                if (s.failureCategory() != null) {
                    sb.append("\u2502   \u2502   \u2514\u2500\u2500 failure_category: ")
                            .append(s.failureCategory()).append("\n");
                }
                if (s.errorMessage() != null) {
                    sb.append("\u2502   \u2502   \u2514\u2500\u2500 error: ")
                            .append(s.errorMessage()).append("\n");
                }
            }
        }
        return sb.toString();
    }
}

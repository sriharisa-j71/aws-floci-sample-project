package com.example.regression.shell;

import com.example.regression.report.ConsoleReporter;
import com.example.regression.report.JsonReporter;
import com.example.regression.report.ReportGenerator;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.util.UUID;

@ShellComponent
public class ReportCommand {

    private final ReportGenerator reportGenerator;
    private final ConsoleReporter consoleReporter;
    private final JsonReporter jsonReporter;

    public ReportCommand(ReportGenerator reportGenerator,
                          ConsoleReporter consoleReporter,
                          JsonReporter jsonReporter) {
        this.reportGenerator = reportGenerator;
        this.consoleReporter = consoleReporter;
        this.jsonReporter = jsonReporter;
    }

    @ShellMethod(key = "report", value = "Generate report for a run")
    public String report(
            @ShellOption(value = "--run-id", help = "Run ID") String runId,
            @ShellOption(value = "--format", defaultValue = "text") String format,
            @ShellOption(value = "--output", defaultValue = ShellOption.NULL) String output) {
        var uuid = UUID.fromString(runId);
        var model = reportGenerator.generate(uuid);

        return switch (format) {
            case "json" -> jsonReporter.render(model);
            case "html" -> {
                var html = "<html><body><h1>Report: " + runId + "</h1><pre>" + jsonReporter.render(model) + "</pre></body></html>";
                if (output != null) {
                    try {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(output), html);
                        yield "HTML report written to " + output;
                    } catch (Exception e) {
                        yield "Error writing HTML: " + e.getMessage();
                    }
                }
                yield html;
            }
            default -> consoleReporter.renderSummaryFromModel(model);
        };
    }
}

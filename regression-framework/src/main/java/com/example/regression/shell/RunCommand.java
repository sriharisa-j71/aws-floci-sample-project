package com.example.regression.shell;

import com.example.regression.report.ConsoleReporter;
import com.example.regression.runner.TestSuiteRunner;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

@ShellComponent
public class RunCommand {

    private final TestSuiteRunner suiteRunner;
    private final ConsoleReporter consoleReporter;

    public RunCommand(TestSuiteRunner suiteRunner, ConsoleReporter consoleReporter) {
        this.suiteRunner = suiteRunner;
        this.consoleReporter = consoleReporter;
    }

    @ShellMethod(key = "run", value = "Execute a test suite")
    public String run(
            @ShellOption(value = "--suite", help = "Suite name") String suite,
            @ShellOption(value = "--data", help = "Data file", defaultValue = ShellOption.NULL) String data,
            @ShellOption(value = "--quiet", help = "Suppress per-case progress", defaultValue = "false") boolean quiet) {
        try {
            var result = suiteRunner.executeSuite(suite, data);
            var output = consoleReporter.renderSummary(result);
            System.out.println(output);
            return result.status();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
            return "ERROR";
        }
    }
}

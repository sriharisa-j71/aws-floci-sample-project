package com.example.regression.report;

import org.springframework.stereotype.Component;

@Component
public class ConsoleProgress {

    private int current = 0;
    private int total = 0;

    public void startSuite(String suiteName, int totalCases) {
        this.current = 0;
        this.total = totalCases;
        System.out.println("Starting suite: " + suiteName + " (" + totalCases + " cases)");
    }

    public void reportCase(String caseName, String stepName, String status, long durationMs) {
        current++;
        String icon = switch (status) {
            case "PASSED" -> "OK";
            case "FAILED" -> "FAIL";
            case "ERROR" -> "ERROR";
            case "SKIPPED" -> "SKIP";
            default -> "?";
        };
        System.out.printf("[%d/%d] %s: %-30s %s (%s)%n",
                current, total, caseName, stepName, icon, durationMs + "ms");
    }

    public void endSuite() {
        System.out.println();
    }
}

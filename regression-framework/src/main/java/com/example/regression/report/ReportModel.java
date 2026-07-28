package com.example.regression.report;

import com.example.regression.model.TestCaseResult;
import com.example.regression.model.TestRun;
import com.example.regression.model.TestStepResult;

import java.util.List;
import java.util.Map;

public record ReportModel(
        TestRun run,
        List<CaseEntry> cases
) {
    public record CaseEntry(
            TestCaseResult result,
            List<StepEntry> steps
    ) {}

    public record StepEntry(
            TestStepResult result,
            List<VerificationEntry> verifications
    ) {}

    public record VerificationEntry(
            String verifierType,
            boolean passed,
            Map<String, Object> actual,
            Map<String, Object> expected,
            String message
    ) {}
}

package com.example.regression.context;

import com.example.regression.model.TestStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TestStepContext {

    private final TestCaseContext parent;
    private final TestStep step;
    private Map<String, Object> response;
    private final List<com.example.regression.model.VerificationResult> verificationResults = new ArrayList<>();

    public TestStepContext(TestCaseContext parent, TestStep step) {
        this.parent = parent;
        this.step = step;
    }

    public TestCaseContext parent() { return parent; }
    public TestStep step() { return step; }
    public Map<String, Object> response() { return response; }
    public void response(Map<String, Object> response) { this.response = response; }
    public List<com.example.regression.model.VerificationResult> verificationResults() {
        return verificationResults;
    }
}

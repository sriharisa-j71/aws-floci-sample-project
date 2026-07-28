package com.example.regression.context;

import com.example.regression.model.TestCase;

import java.util.HashMap;
import java.util.Map;

public class TestCaseContext {

    private final SuiteContext parent;
    private final TestCase testCase;
    private final Map<String, Object> dataRow;
    private final Map<String, Object> stepResponses = new HashMap<>();

    public TestCaseContext(SuiteContext parent, TestCase testCase, Map<String, Object> dataRow) {
        this.parent = parent;
        this.testCase = testCase;
        this.dataRow = dataRow;
    }

    public SuiteContext parent() { return parent; }
    public TestCase testCase() { return testCase; }
    public Map<String, Object> dataRow() { return dataRow; }
    public Map<String, Object> stepResponses() { return stepResponses; }
}

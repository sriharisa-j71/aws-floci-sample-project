package com.example.regression.context;

import com.example.regression.config.AppConfig;
import com.example.regression.model.TestSuite;

import java.util.HashMap;
import java.util.Map;

public class SuiteContext {

    private final TestSuite suite;
    private final AppConfig config;
    private final Map<String, Object> sharedData = new HashMap<>();
    private final Map<String, Object> suiteResultBuilder = new HashMap<>();

    public SuiteContext(TestSuite suite, AppConfig config) {
        this.suite = suite;
        this.config = config;
    }

    public TestSuite suite() { return suite; }
    public AppConfig config() { return config; }
    public Map<String, Object> sharedData() { return sharedData; }
    public Map<String, Object> suiteResultBuilder() { return suiteResultBuilder; }

    public Map<String, Object> snapshotSharedData() {
        return Map.copyOf(sharedData);
    }
}

package com.example.regression.model;

import java.util.List;
import java.util.Map;

public record TestSuite(
    String suiteName,
    Integer schemaVersion,
    List<TestCase> cases,
    List<LifecycleHook> beforeAll,
    List<LifecycleHook> afterAll,
    Map<String, Object> config,
    List<String> tags
) {}

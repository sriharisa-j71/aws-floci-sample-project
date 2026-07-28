package com.example.regression.model;

import java.util.List;
import java.util.Map;

public record TestCase(
    String caseName,
    List<TestStep> steps,
    List<LifecycleHook> beforeEach,
    List<LifecycleHook> afterEach,
    String dataSource,
    Map<String, Object> config,
    Boolean flaky
) {}

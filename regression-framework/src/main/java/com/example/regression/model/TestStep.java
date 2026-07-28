package com.example.regression.model;

import java.util.List;
import java.util.Map;

public record TestStep(
    String stepName,
    Action action,
    List<Verification> verifications,
    List<LifecycleHook> before,
    List<LifecycleHook> after,
    Integer retryCount,
    Long timeoutMs,
    Map<String, Object> config,
    Boolean flaky
) {}

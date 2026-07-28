package com.example.regression.model;

import java.util.List;

public record LifecycleHook(
    String hookType,
    List<Action> actions,
    String description,
    Boolean stopOnFailure,
    Integer order
) {}

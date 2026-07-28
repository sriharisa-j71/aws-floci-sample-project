package com.example.regression.model;

import java.util.Map;

public record Action(
    String actionType,
    Map<String, Object> params
) {}

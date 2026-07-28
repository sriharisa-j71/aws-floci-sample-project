package com.example.regression.action;

import com.example.regression.context.ContextResolver;
import com.example.regression.context.TestStepContext;
import com.example.regression.model.Action;

import java.util.Map;

public interface ActionHandler {
    String supportedActionType();
    Map<String, Object> handle(Action action, TestStepContext context, ContextResolver resolver);
}

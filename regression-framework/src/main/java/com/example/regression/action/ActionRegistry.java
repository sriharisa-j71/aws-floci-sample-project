package com.example.regression.action;

import com.example.regression.exception.ActionExecutionException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ActionRegistry {

    private final Map<String, ActionHandler> handlers = new HashMap<>();

    public ActionRegistry(List<ActionHandler> handlerList) {
        for (var handler : handlerList) {
            var existing = handlers.putIfAbsent(handler.supportedActionType(), handler);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate handler for action type: " + handler.supportedActionType());
            }
        }
    }

    public ActionHandler getHandler(String actionType) {
        var handler = handlers.get(actionType);
        if (handler == null) {
            throw new ActionExecutionException("No handler for action type: " + actionType);
        }
        return handler;
    }

    public Map<String, ActionHandler> allHandlers() {
        return Map.copyOf(handlers);
    }
}

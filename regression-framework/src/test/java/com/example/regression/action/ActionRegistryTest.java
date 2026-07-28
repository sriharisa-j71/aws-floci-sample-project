package com.example.regression.action;

import com.example.regression.context.ContextResolver;
import com.example.regression.context.TestStepContext;
import com.example.regression.model.Action;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActionRegistryTest {

    @Test
    void registersHandlersFromBeanList() {
        var handler = new TestHandler();
        var registry = new ActionRegistry(List.of(handler));
        assertNotNull(registry.getHandler("test:action"));
    }

    @Test
    void duplicateHandlerThrows() {
        var handler = new TestHandler();
        assertThrows(IllegalStateException.class, () -> new ActionRegistry(List.of(handler, handler)));
    }

    @Test
    void unknownHandlerThrows() {
        var registry = new ActionRegistry(List.of());
        assertThrows(RuntimeException.class, () -> registry.getHandler("unknown:action"));
    }

    static class TestHandler implements ActionHandler {
        @Override
        public String supportedActionType() { return "test:action"; }

        @Override
        public Map<String, Object> handle(Action action, TestStepContext context, ContextResolver resolver) {
            return Map.of("result", "ok");
        }
    }
}

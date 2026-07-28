package com.example.regression.action;

import com.example.regression.context.ContextResolver;
import com.example.regression.context.TestStepContext;
import com.example.regression.exception.ActionExecutionException;
import com.example.regression.model.Action;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Component
public class LambdaActionHandler implements ActionHandler {

    private final LambdaClient lambdaClient;

    public LambdaActionHandler(LambdaClient lambdaClient) {
        this.lambdaClient = lambdaClient;
    }

    @Override
    public String supportedActionType() {
        return "lambda:invoke";
    }

    @Override
    public Map<String, Object> handle(Action action, TestStepContext context, ContextResolver resolver) {
        return switch (action.actionType()) {
            case "lambda:invoke" -> handleInvoke(action);
            default -> throw new ActionExecutionException("Unsupported Lambda action: " + action.actionType());
        };
    }

    private Map<String, Object> handleInvoke(Action action) {
        var params = action.params();
        var functionName = (String) params.get("functionName");
        var payload = (String) params.getOrDefault("payload", "{}");
        var request = InvokeRequest.builder()
                .functionName(functionName)
                .payload(SdkBytes.fromString(payload, StandardCharsets.UTF_8))
                .build();
        var response = lambdaClient.invoke(request);
        var result = new HashMap<String, Object>();
        result.put("statusCode", response.statusCode());
        result.put("payload", response.payload().asUtf8String());
        result.put("functionError", response.functionError());
        result.put("logResult", response.logResult());
        return result;
    }
}

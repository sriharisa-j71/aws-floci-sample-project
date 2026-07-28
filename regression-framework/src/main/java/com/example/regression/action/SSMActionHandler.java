package com.example.regression.action;

import com.example.regression.context.ContextResolver;
import com.example.regression.context.TestStepContext;
import com.example.regression.exception.ActionExecutionException;
import com.example.regression.model.Action;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.*;

import java.util.HashMap;
import java.util.Map;

@Component
public class SSMActionHandler implements ActionHandler {

    private final SsmClient ssmClient;

    public SSMActionHandler(SsmClient ssmClient) {
        this.ssmClient = ssmClient;
    }

    @Override
    public String supportedActionType() {
        return "ssm:putParameter";
    }

    @Override
    public Map<String, Object> handle(Action action, TestStepContext context, ContextResolver resolver) {
        return switch (action.actionType()) {
            case "ssm:putParameter" -> handlePutParameter(action);
            case "ssm:getParameter" -> handleGetParameter(action);
            case "ssm:deleteParameter" -> handleDeleteParameter(action);
            default -> throw new ActionExecutionException("Unsupported SSM action: " + action.actionType());
        };
    }

    private Map<String, Object> handlePutParameter(Action action) {
        var params = action.params();
        var name = (String) params.get("name");
        var value = (String) params.get("value");
        var type = (String) params.getOrDefault("type", "String");
        var request = PutParameterRequest.builder()
                .name(name)
                .value(value)
                .type(type)
                .overwrite(true)
                .build();
        var response = ssmClient.putParameter(request);
        var result = new HashMap<String, Object>();
        result.put("version", response.version());
        result.put("tier", response.tierAsString());
        result.put("statusCode", 200);
        return result;
    }

    private Map<String, Object> handleGetParameter(Action action) {
        var params = action.params();
        var name = (String) params.get("name");
        var request = GetParameterRequest.builder()
                .name(name)
                .build();
        var response = ssmClient.getParameter(request);
        var parameter = response.parameter();
        var result = new HashMap<String, Object>();
        result.put("name", parameter.name());
        result.put("value", parameter.value());
        result.put("type", parameter.typeAsString());
        result.put("version", parameter.version());
        result.put("statusCode", 200);
        return result;
    }

    private Map<String, Object> handleDeleteParameter(Action action) {
        var params = action.params();
        var name = (String) params.get("name");
        ssmClient.deleteParameter(DeleteParameterRequest.builder().name(name).build());
        return Map.of("deleted", true, "statusCode", 200);
    }
}

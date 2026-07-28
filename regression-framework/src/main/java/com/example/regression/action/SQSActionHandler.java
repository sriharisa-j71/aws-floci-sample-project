package com.example.regression.action;

import com.example.regression.context.ContextResolver;
import com.example.regression.context.TestStepContext;
import com.example.regression.exception.ActionExecutionException;
import com.example.regression.model.Action;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class SQSActionHandler implements ActionHandler {

    private final SqsClient sqsClient;

    public SQSActionHandler(SqsClient sqsClient) {
        this.sqsClient = sqsClient;
    }

    @Override
    public String supportedActionType() {
        return "sqs:sendMessage";
    }

    @Override
    public Map<String, Object> handle(Action action, TestStepContext context, ContextResolver resolver) {
        return switch (action.actionType()) {
            case "sqs:sendMessage" -> handleSendMessage(action);
            case "sqs:receiveMessage" -> handleReceiveMessage(action);
            case "sqs:purgeQueue" -> handlePurgeQueue(action);
            case "sqs:getQueueAttributes" -> handleGetQueueAttributes(action);
            default -> throw new ActionExecutionException("Unsupported SQS action: " + action.actionType());
        };
    }

    private Map<String, Object> handleSendMessage(Action action) {
        var params = action.params();
        var queueUrl = (String) params.get("queueUrl");
        var messageBody = (String) params.get("messageBody");
        var request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(messageBody)
                .build();
        var response = sqsClient.sendMessage(request);
        var result = new HashMap<String, Object>();
        result.put("messageId", response.messageId());
        result.put("sequenceNumber", response.sequenceNumber());
        result.put("statusCode", 200);
        return result;
    }

    private Map<String, Object> handleReceiveMessage(Action action) {
        var params = action.params();
        var queueUrl = (String) params.get("queueUrl");
        var maxMessages = (Integer) params.getOrDefault("maxMessages", 10);
        var request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .build();
        var response = sqsClient.receiveMessage(request);
        var messages = response.messages().stream()
                .map(m -> Map.of(
                        "messageId", m.messageId(),
                        "body", m.body(),
                        "receiptHandle", m.receiptHandle()
                ))
                .collect(Collectors.toList());
        var result = new HashMap<String, Object>();
        result.put("messages", messages);
        result.put("count", messages.size());
        result.put("statusCode", 200);
        return result;
    }

    private Map<String, Object> handlePurgeQueue(Action action) {
        var params = action.params();
        var queueUrl = (String) params.get("queueUrl");
        sqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(queueUrl).build());
        return Map.of("purged", true, "statusCode", 200);
    }

    private Map<String, Object> handleGetQueueAttributes(Action action) {
        var params = action.params();
        var queueUrl = (String) params.get("queueUrl");
        var request = GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.ALL)
                .build();
        var response = sqsClient.getQueueAttributes(request);
        var result = new HashMap<String, Object>();
        result.put("attributes", response.attributes());
        result.put("statusCode", 200);
        return result;
    }
}

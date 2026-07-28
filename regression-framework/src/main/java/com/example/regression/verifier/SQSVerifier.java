package com.example.regression.verifier;

import com.example.regression.context.TestStepContext;
import com.example.regression.model.VerificationResult;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.Map;

@Component
public class SQSVerifier implements Verifier {

    private final SqsClient sqsClient;

    public SQSVerifier(SqsClient sqsClient) {
        this.sqsClient = sqsClient;
    }

    @Override
    public String supportedVerifierType() {
        return "messageCount";
    }

    @Override
    public VerificationResult verify(Map<String, Object> actual, Map<String, Object> expected, TestStepContext context) {
        return switch ((String) expected.getOrDefault("type", "messageCount")) {
            case "messageBodyMatches" -> verifyMessageBody(expected);
            default -> verifyMessageCount(expected);
        };
    }

    private VerificationResult verifyMessageCount(Map<String, Object> expected) {
        var queueUrl = (String) expected.get("queueUrl");
        var expectedCount = ((Number) expected.get("count")).intValue();
        var request = GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                .build();
        var response = sqsClient.getQueueAttributes(request);
        var actualCount = Integer.parseInt(response.attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
        var passed = actualCount == expectedCount;
        return new VerificationResult(null, "step", null, "messageCount", passed,
                Map.of("count", actualCount),
                Map.of("count", expectedCount),
                passed ? "OK" : "Expected %d messages but got %d".formatted(expectedCount, actualCount),
                null);
    }

    private VerificationResult verifyMessageBody(Map<String, Object> expected) {
        var queueUrl = (String) expected.get("queueUrl");
        var expectedBody = (String) expected.get("body");
        var request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .build();
        var response = sqsClient.receiveMessage(request);
        var actualBody = response.messages().isEmpty() ? "" : response.messages().getFirst().body();
        var passed = actualBody.contains(expectedBody);
        return new VerificationResult(null, "step", null, "messageBodyMatches", passed,
                Map.of("body", actualBody),
                Map.of("body", expectedBody),
                passed ? "OK" : "Expected message body to contain '%s'".formatted(expectedBody),
                null);
    }
}

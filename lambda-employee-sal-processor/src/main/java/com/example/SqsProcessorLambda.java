package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class SqsProcessorLambda implements RequestHandler<SQSEvent, String> {

    private static final String AWS_ENDPOINT = System.getenv().getOrDefault("AWS_ENDPOINT_URL",
            "http://floci:4566");
    private static final String AWS_REGION = System.getenv().getOrDefault("AWS_DEFAULT_REGION",
            "us-east-1");
    private static final String SSM_API_ENDPOINT_PATH = System.getenv()
            .getOrDefault("SSM_API_ENDPOINT_PATH", "/emp/api/endpoint");

    private final SqsClient sqs;
    private final SsmClient ssm;
    private final ObjectMapper mapper;
    private final String apiEndpoint;
    private final String sqsQueueUrl;

    public SqsProcessorLambda() {
        var creds = DefaultCredentialsProvider.create();
        var region = Region.of(AWS_REGION);
        var endpoint = URI.create(AWS_ENDPOINT);

        this.ssm = SsmClient.builder()
                .region(region).credentialsProvider(creds)
                .endpointOverride(endpoint).build();
        this.sqs = SqsClient.builder()
                .region(region).credentialsProvider(creds)
                .endpointOverride(endpoint).build();
        this.mapper = new ObjectMapper();

        this.apiEndpoint = readSsmParameter(SSM_API_ENDPOINT_PATH);
        this.sqsQueueUrl = System.getenv().getOrDefault("SQS_QUEUE_URL",
                "http://localhost:4566/000000000000/emp-processing");
    }

    private String readSsmParameter(String paramPath) {
        try {
            var req = GetParameterRequest.builder().name(paramPath).build();
            return ssm.getParameter(req).parameter().value();
        } catch (Exception e) {
            System.err.println("SSM read failed for " + paramPath + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public String handleRequest(SQSEvent event, Context context) {
        context.getLogger().log("API endpoint from SSM: " + apiEndpoint + "\n");
        int processed = 0;

        for (SQSEvent.SQSMessage msg : event.getRecords()) {
            try {
                EmployeeRecord emp = mapper.readValue(msg.getBody(), EmployeeRecord.class);
                context.getLogger().log("Processing emp_id=" + emp.emp_id() + "\n");

                boolean exists = checkEmployeeExists(emp.emp_id(), emp.emp_name());
                context.getLogger().log("  Employee " + emp.emp_id() + " (" + emp.emp_name()
                        + ") exists=" + exists + "\n");

                if (sqsQueueUrl != null && msg.getReceiptHandle() != null) {
                    sqs.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(sqsQueueUrl)
                            .receiptHandle(msg.getReceiptHandle())
                            .build());
                }
                processed++;
            } catch (Exception e) {
                context.getLogger().log("Error processing message: " + e.getMessage() + "\n");
            }
        }

        String result = "Processed " + processed + " messages";
        context.getLogger().log(result + "\n");
        return result;
    }

    private boolean checkEmployeeExists(int empId, String empName) {
        if (apiEndpoint == null || apiEndpoint.isEmpty()) {
            System.out.println("  No API endpoint configured, skipping check");
            return true;
        }
        try {
            URL url = URI.create(apiEndpoint + "/employee/" + empId).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int status = conn.getResponseCode();
            if (status == 200) {
                try (var reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String response = reader.lines().collect(Collectors.joining());
                    System.out.println("  API response for emp " + empId + ": " + response);
                    return response.contains("\"exists\": true") || response.contains("\"exists\":true");
                }
            }
            return false;
        } catch (Exception e) {
            System.out.println("  API call failed for emp " + empId + ": " + e.getMessage());
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        var endpoint = URI.create(System.getenv().getOrDefault("AWS_ENDPOINT_URL", "http://localhost:4566"));
        var region = Region.US_EAST_1;
        var creds = DefaultCredentialsProvider.create();

        var ssm = SsmClient.builder()
                .region(region).credentialsProvider(creds)
                .endpointOverride(endpoint).build();
        var sqs = SqsClient.builder()
                .region(region).credentialsProvider(creds)
                .endpointOverride(endpoint).build();
        var mapper = new ObjectMapper();

        String apiEndpoint = ssm.getParameter(
                GetParameterRequest.builder()
                        .name(System.getenv().getOrDefault("SSM_API_ENDPOINT_PATH", "/emp/api/endpoint"))
                        .build())
                .parameter().value();

        String queueUrl = System.getenv().getOrDefault("SQS_QUEUE_URL",
                "http://localhost:4566/000000000000/emp-processing");

        System.out.println("Polling SQS queue: " + queueUrl);
        System.out.println("API endpoint from SSM: " + apiEndpoint);

        var receiveReq = software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(20)
                .build();

        while (true) {
            var response = sqs.receiveMessage(receiveReq);
            if (response.messages().isEmpty()) {
                System.out.println("No messages, waiting...");
                continue;
            }
            for (var msg : response.messages()) {
                try {
                    EmployeeRecord emp = mapper.readValue(msg.body(), EmployeeRecord.class);
                    System.out.println("Processing: emp_id=" + emp.emp_id() + ", name=" + emp.emp_name());

                    URL url = URI.create(apiEndpoint + "/employee/" + emp.emp_id()).toURL();
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);

                    try (var reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String apiResp = reader.lines().collect(Collectors.joining());
                        System.out.println("  API: " + apiResp);
                    }

                    sqs.deleteMessage(
                            software.amazon.awssdk.services.sqs.model.DeleteMessageRequest.builder()
                                    .queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).build());
                    System.out.println("  Done.");
                } catch (Exception e) {
                    System.err.println("Error: " + e.getMessage());
                }
            }
        }
    }
}

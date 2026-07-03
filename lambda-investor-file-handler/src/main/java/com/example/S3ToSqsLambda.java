package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class S3ToSqsLambda implements RequestHandler<S3Event, String> {

    private static final Logger log = LoggerFactory.getLogger(S3ToSqsLambda.class);

    private static final String AWS_ENDPOINT = System.getenv().getOrDefault("AWS_ENDPOINT_URL",
            "http://floci:4566");
    private static final String AWS_REGION = System.getenv().getOrDefault("AWS_DEFAULT_REGION",
            "us-east-1");
    private static final String SSM_SQS_QUEUE_PATH = System.getenv()
            .getOrDefault("SSM_SQS_QUEUE_PATH", "/investor/sqs/queue-url");
    private static final String SSM_API_ENDPOINT_PATH = System.getenv()
            .getOrDefault("SSM_API_ENDPOINT_PATH", "/investor/api/endpoint");

    private final S3Client s3;
    private final SqsClient sqs;
    private final SsmClient ssm;
    private final ObjectMapper mapper;
    private final String sqsQueueUrl;
    private final String apiEndpoint;

    public S3ToSqsLambda() {
        var creds = DefaultCredentialsProvider.create();
        var region = Region.of(AWS_REGION);
        var endpoint = URI.create(AWS_ENDPOINT);

        this.ssm = SsmClient.builder()
                .region(region).credentialsProvider(creds)
                .endpointOverride(endpoint).build();
        this.sqs = SqsClient.builder()
                .region(region).credentialsProvider(creds)
                .endpointOverride(endpoint).build();
        this.s3 = S3Client.builder()
                .region(region).credentialsProvider(creds)
                .endpointOverride(endpoint).forcePathStyle(true).build();
        this.mapper = new ObjectMapper();

        this.sqsQueueUrl = readSsmParameter(SSM_SQS_QUEUE_PATH);
        this.apiEndpoint = readSsmParameter(SSM_API_ENDPOINT_PATH);
    }

    private String readSsmParameter(String paramPath) {
        try {
            var req = GetParameterRequest.builder().name(paramPath).build();
            return ssm.getParameter(req).parameter().value();
        } catch (Exception e) {
            log.error("SSM read failed for {}: {}", paramPath, e.getMessage());
            return null;
        }
    }

    @Override
    public String handleRequest(S3Event event, Context context) {
        log.info("SQS queue URL from SSM: {}", sqsQueueUrl);
        log.info("API endpoint from SSM: {}", apiEndpoint);

        int totalMessages = 0;

        for (S3EventNotification.S3EventNotificationRecord record : event.getRecords()) {
            String bucket = record.getS3().getBucket().getName();
            String key = record.getS3().getObject().getKey();
            log.info("Processing s3://{}/{}", bucket, key);

            try {
                String content = readS3Object(bucket, key);
                String[] lines = content.split("\n");
                boolean header = true;

                for (String line : lines) {
                    if (header) { header = false; continue; }
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    String[] parts = line.split("\\|");
                    if (parts.length < 7) continue;

                    InvestorRecord rec = new InvestorRecord(
                            Integer.parseInt(parts[0].trim()),
                            parts[1].trim(),
                            Double.parseDouble(parts[2].trim()),
                            parts[3].trim(),
                            parts[4].trim(),
                            parts[5].trim(),
                            parts[6].trim()
                    );

                    String msgBody = mapper.writeValueAsString(rec);
                    sqs.sendMessage(SendMessageRequest.builder()
                            .queueUrl(sqsQueueUrl)
                            .messageBody(msgBody)
                            .build());
                    totalMessages++;
                    log.info("Published: customer_id={}", rec.customer_id());
                }
            } catch (Exception e) {
                log.error("Error processing {}: {}", key, e.getMessage());
            }
        }

        String result = "Published " + totalMessages + " messages to SQS";
        log.info(result);
        return result;
    }

    private String readS3Object(String bucket, String key) {
        var request = GetObjectRequest.builder().bucket(bucket).key(key).build();
        try (var is = s3.getObject(request);
             var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read S3 object s3://" + bucket + "/" + key, e);
        }
    }

    public static void main(String[] args) throws Exception {
        var endpoint = URI.create(System.getenv().getOrDefault("AWS_ENDPOINT_URL", "http://localhost:4566"));
        var bucket = System.getenv().getOrDefault("S3_BUCKET", "investor-output");
        var prefix = System.getenv().getOrDefault("S3_PREFIX", "data/");
        var pollInterval = Long.parseLong(System.getenv().getOrDefault("S3_POLL_INTERVAL", "5"));
        var region = Region.US_EAST_1;
        var creds = DefaultCredentialsProvider.create();

        var ssm = SsmClient.builder()
                .region(region).credentialsProvider(creds)
                .endpointOverride(endpoint).build();
        var s3 = S3Client.builder()
                .region(region).credentialsProvider(creds)
                .endpointOverride(endpoint).forcePathStyle(true).build();
        var sqs = SqsClient.builder()
                .region(region).credentialsProvider(creds)
                .endpointOverride(endpoint).build();
        var mapper = new ObjectMapper();

        String queueUrl = ssm.getParameter(
                GetParameterRequest.builder()
                        .name(System.getenv().getOrDefault("SSM_SQS_QUEUE_PATH", "/investor/sqs/queue-url"))
                        .build())
                .parameter().value();

        var seen = new java.util.HashSet<String>();
        log.info("Watching S3 bucket '{}' prefix '{}' (poll every {}s)", bucket, prefix, pollInterval);

        while (true) {
            try {
                var listReq = software.amazon.awssdk.services.s3.model.ListObjectsV2Request.builder()
                        .bucket(bucket).prefix(prefix).build();
                var listRes = s3.listObjectsV2(listReq);

                for (var obj : listRes.contents()) {
                    String key = obj.key();
                    if (!key.endsWith(".csv")) continue;
                    if (seen.contains(key)) continue;

                    seen.add(key);
                    log.info("Processing: {}", key);

                    var getReq = GetObjectRequest.builder().bucket(bucket).key(key).build();
                    try (var is = s3.getObject(getReq);
                         var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String content = reader.lines().collect(Collectors.joining("\n"));
                        String[] lines = content.split("\n");
                        boolean header = true;
                        for (String line : lines) {
                            if (header) { header = false; continue; }
                            line = line.trim();
                            if (line.isEmpty()) continue;
                            String[] parts = line.split("\\|");
                            if (parts.length < 7) continue;
                            InvestorRecord rec = new InvestorRecord(
                                    Integer.parseInt(parts[0].trim()),
                                    parts[1].trim(),
                                    Double.parseDouble(parts[2].trim()),
                                    parts[3].trim(),
                                    parts[4].trim(),
                                    parts[5].trim(),
                                    parts[6].trim()
                            );
                            sqs.sendMessage(SendMessageRequest.builder()
                                    .queueUrl(queueUrl).messageBody(mapper.writeValueAsString(rec)).build());
                            log.info("Published: customer_id={}", rec.customer_id());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error during poll cycle: {}", e.getMessage());
            }

            try { Thread.sleep(pollInterval * 1000); } catch (InterruptedException e) { break; }
        }

        ssm.close();
        s3.close();
        sqs.close();
    }
}

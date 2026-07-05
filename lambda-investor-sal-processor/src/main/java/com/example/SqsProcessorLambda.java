package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SqsProcessorLambda implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final Logger log = LoggerFactory.getLogger(SqsProcessorLambda.class);

    private static final String AWS_ENDPOINT = System.getenv().getOrDefault("AWS_ENDPOINT_URL",
            "http://floci:4566");
    private static final String AWS_REGION = System.getenv().getOrDefault("AWS_DEFAULT_REGION",
            "us-east-1");
    private static final String SSM_API_ENDPOINT_PATH = System.getenv()
            .getOrDefault("SSM_API_ENDPOINT_PATH", "/investor/api/endpoint");
    private static final String DB_DSN = System.getenv().getOrDefault("DB_DSN",
            "jdbc:postgresql://postgres:5432/postgres?user=admin&password=secret123");

    private final SsmClient ssm;
    private final ObjectMapper mapper;
    private final String apiEndpoint;
    private Connection dbConn;

    public SqsProcessorLambda() {
        var creds = DefaultCredentialsProvider.create();
        var region = Region.of(AWS_REGION);
        var endpoint = URI.create(AWS_ENDPOINT);

        this.ssm = SsmClient.builder()
                .region(region).credentialsProvider(creds)
                .endpointOverride(endpoint).build();
        this.mapper = new ObjectMapper();

        this.apiEndpoint = readSsmParameter(SSM_API_ENDPOINT_PATH);

        try {
            this.dbConn = DriverManager.getConnection(DB_DSN);
            log.info("Connected to PostgreSQL");
        } catch (Exception e) {
            log.warn("Failed to connect to PostgreSQL: {}", e.getMessage());
            this.dbConn = null;
        }
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
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        log.info("API endpoint from SSM: {}", apiEndpoint);
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();

        for (SQSEvent.SQSMessage msg : event.getRecords()) {
            try {
                InvestorRecord inv = mapper.readValue(msg.getBody(), InvestorRecord.class);
                log.info("Processing customer_id={}", inv.customer_id());

                boolean exists = checkInvestorExists(inv.customer_id(), inv.full_name());
                log.info("Investor {} ({}) exists={}", inv.customer_id(), inv.full_name(), exists);

                if (!exists) {
                    log.warn("Investor {} does not exist, sending to DLQ", inv.customer_id());
                    failures.add(new SQSBatchResponse.BatchItemFailure(msg.getMessageId()));
                    continue;
                }

                RiskAssessment assessment = queryRiskScore(inv.customer_id());
                if (assessment != null) {
                    saveRiskAssessment(inv.customer_id(), assessment);
                } else {
                    log.info("No daily risk score found for investor {}", inv.customer_id());
                }
            } catch (Exception e) {
                log.error("Error processing message {}: {}", msg.getMessageId(), e.getMessage());
                failures.add(new SQSBatchResponse.BatchItemFailure(msg.getMessageId()));
            }
        }

        String result = "Processed " + event.getRecords().size() + " messages, " +
                failures.size() + " failed";
        log.info(result);
        return new SQSBatchResponse(failures);
    }

    private boolean checkInvestorExists(int customerId, String fullName) {
        if (apiEndpoint == null || apiEndpoint.isEmpty()) {
            log.info("No API endpoint configured, skipping check");
            return true;
        }
        try {
            URL url = URI.create(apiEndpoint + "/investor/" + customerId).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int status = conn.getResponseCode();
            if (status == 200) {
                try (var reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String response = reader.lines().collect(Collectors.joining());
                    log.info("API response for investor {}: {}", customerId, response);
                    return response.contains("\"exists\": true") || response.contains("\"exists\":true");
                }
            }
            return false;
        } catch (Exception e) {
            log.info("API call failed for investor {}: {}", customerId, e.getMessage());
            return false;
        }
    }

    record RiskAssessment(
            int customerId,
            String riskProfile,
            double compositeScore,
            String riskCategory,
            String calculationDate
    ) {}

    private RiskAssessment queryRiskScore(int customerId) {
        if (dbConn == null) {
            log.warn("No DB connection, skipping risk score query");
            return null;
        }
        String sql = """
                SELECT risk_profile, composite_score, risk_category,
                       calculation_date::text
                FROM daily_risk_scores
                WHERE customer_id = $1
                  AND calculation_date = CURRENT_DATE
                ORDER BY calculation_date DESC
                LIMIT 1
                """.replace("$1", "?");
        try (var stmt = dbConn.prepareStatement(sql)) {
            stmt.setInt(1, customerId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new RiskAssessment(
                            customerId,
                            rs.getString("risk_profile"),
                            rs.getDouble("composite_score"),
                            rs.getString("risk_category"),
                            rs.getString("calculation_date")
                    );
                }
            }
        } catch (Exception e) {
            log.warn("Risk score query failed for customer {}: {}", customerId, e.getMessage());
        }
        return null;
    }

    private void saveRiskAssessment(int customerId, RiskAssessment assessment) {
        if (apiEndpoint == null || apiEndpoint.isEmpty()) {
            log.info("No API endpoint configured, skipping save");
            return;
        }
        try {
            URL url = URI.create(apiEndpoint + "/risk-score/" + customerId).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            Map<String, Object> body = Map.of(
                    "customer_id", assessment.customerId(),
                    "risk_profile", assessment.riskProfile(),
                    "composite_score", assessment.compositeScore(),
                    "risk_category", assessment.riskCategory(),
                    "calculation_date", assessment.calculationDate()
            );
            try (OutputStream os = conn.getOutputStream()) {
                os.write(mapper.writeValueAsBytes(body));
            }

            int status = conn.getResponseCode();
            String response;
            try (var reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                response = reader.lines().collect(Collectors.joining());
            }
            log.info("Risk assessment saved for customer {}: status={}, response={}",
                    customerId, status, response);
        } catch (Exception e) {
            log.warn("Failed to save risk assessment for customer {}: {}", customerId, e.getMessage());
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
                        .name(System.getenv().getOrDefault("SSM_API_ENDPOINT_PATH", "/investor/api/endpoint"))
                        .build())
                .parameter().value();

        String queueUrl = System.getenv().getOrDefault("SQS_QUEUE_URL",
                "http://localhost:4566/000000000000/investor-processing");

        log.info("Polling SQS queue: {}", queueUrl);
        log.info("API endpoint from SSM: {}", apiEndpoint);

        var receiveReq = software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(20)
                .build();

        while (true) {
            var response = sqs.receiveMessage(receiveReq);
            if (response.messages().isEmpty()) {
                log.info("No messages, waiting...");
                continue;
            }
            for (var msg : response.messages()) {
                try {
                    InvestorRecord inv = mapper.readValue(msg.body(), InvestorRecord.class);
                    log.info("Processing: customer_id={}, name={}", inv.customer_id(), inv.full_name());

                    URL url = URI.create(apiEndpoint + "/investor/" + inv.customer_id()).toURL();
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);

                    try (var reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String apiResp = reader.lines().collect(Collectors.joining());
                        log.info("API: {}", apiResp);
                    }

                    sqs.deleteMessage(
                            software.amazon.awssdk.services.sqs.model.DeleteMessageRequest.builder()
                                    .queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).build());
                    log.info("Done.");
                } catch (Exception e) {
                    log.error("Error: {}", e.getMessage());
                }
            }
        }
    }
}

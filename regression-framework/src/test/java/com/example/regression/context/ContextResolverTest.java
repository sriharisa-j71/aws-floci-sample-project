package com.example.regression.context;

import com.example.regression.config.AppConfig;
import com.example.regression.model.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextResolverTest {

    private ContextResolver resolver;
    private TestStepContext stepCtx;

    @BeforeEach
    void setUp() {
        resolver = new ContextResolver();

        var suite = new TestSuite("test", 1, List.of(), null, null, Map.of("region", "us-east-1"), null);
        var config = new AppConfig();
        var suiteCtx = new SuiteContext(suite, config);

        suiteCtx.sharedData().put("bucketName", "shared-bucket");

        var testCase = new TestCase("test-case", List.of(), null, null, null, null, null);
        var caseCtx = new TestCaseContext(suiteCtx, testCase, Map.of("investorId", "123", "name", "John"));

        var step = new TestStep("test-step", null, null, null, null, null, null, null, null);
        stepCtx = new TestStepContext(caseCtx, step);
    }

    @Test
    void resolveEnvVar() {
        var result = resolver.resolve("{{env.HOME}}", stepCtx, "test");
        assertEquals(System.getenv("HOME"), result);
    }

    @Test
    void resolveShared() {
        var result = resolver.resolve("{{shared.bucketName}}", stepCtx, "test");
        assertEquals("shared-bucket", result);
    }

    @Test
    void resolveResolved() {
        var result = resolver.resolve("{{resolved.investorId}}", stepCtx, "test");
        assertEquals("123", result);
    }

    @Test
    void resolveConfig() {
        var result = resolver.resolve("{{config.region}}", stepCtx, "test");
        assertEquals("us-east-1", result);
    }

    @Test
    void resolveNestedMap() {
        var params = Map.of("bucket", "{{shared.bucketName}}", "key", "{{resolved.investorId}}");
        var resolved = resolver.resolve(params, stepCtx, "test");
        assertInstanceOf(Map.class, resolved);
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>) resolved;
        assertEquals("shared-bucket", map.get("bucket"));
        assertEquals("123", map.get("key"));
    }

    @Test
    void unknownKeyReturnsOriginalTemplate() {
        var result = resolver.resolve("{{unknown.key}}", stepCtx, "test");
        assertEquals("{{unknown.key}}", result);
    }

    @Test
    void cachingWorks() {
        var r1 = resolver.resolve("{{shared.bucketName}}", stepCtx, "test");
        var r2 = resolver.resolve("{{shared.bucketName}}", stepCtx, "test");
        assertEquals(r1, r2);
    }

    @Test
    void clearCache() {
        resolver.resolve("{{shared.bucketName}}", stepCtx, "test");
        resolver.clearCache();
        var result = resolver.resolve("{{shared.bucketName}}", stepCtx, "test");
        assertEquals("shared-bucket", result);
    }

    @Test
    void resolveStepRef() {
        stepCtx.parent().stepResponses().put("createJob", Map.of("jobRunId", "jr-123"));
        var result = resolver.resolve("{{step.createJob.response.jobRunId}}", stepCtx, "test");
        assertEquals("jr-123", result);
    }

    @Test
    void resolveSplitFilter() {
        var result = resolver.resolve("{{resolved.name|split}}", stepCtx, "test");
        assertEquals("John", result);
    }
}

package com.example.regression.runner;

import com.example.regression.config.AppConfig;
import com.example.regression.context.SuiteContext;
import com.example.regression.db.SuiteResultRepository;
import com.example.regression.runner.TestCaseRunner.CaseExecutionResult;
import com.example.regression.db.TestCaseResultRepository;
import com.example.regression.db.TestRunRepository;
import com.example.regression.db.TestStepResultRepository;
import com.example.regression.db.VerificationResultRepository;
import com.example.regression.lifecycle.LifecycleEngine;
import com.example.regression.model.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
public class TestSuiteRunner {

    private final AppConfig appConfig;
    private final TestCaseRunner caseRunner;
    private final LifecycleEngine lifecycleEngine;
    private final JsonMapper jsonMapper;
    private final TestRunRepository testRunRepository;
    private final SuiteResultRepository suiteResultRepository;
    private final TestCaseResultRepository caseResultRepository;
    private final TestStepResultRepository stepResultRepository;
    private final VerificationResultRepository verificationResultRepository;

    public TestSuiteRunner(AppConfig appConfig,
                           TestCaseRunner caseRunner,
                           LifecycleEngine lifecycleEngine,
                           JsonMapper jsonMapper,
                           TestRunRepository testRunRepository,
                           SuiteResultRepository suiteResultRepository,
                           TestCaseResultRepository caseResultRepository,
                           TestStepResultRepository stepResultRepository,
                           VerificationResultRepository verificationResultRepository) {
        this.appConfig = appConfig;
        this.caseRunner = caseRunner;
        this.lifecycleEngine = lifecycleEngine;
        this.jsonMapper = jsonMapper;
        this.testRunRepository = testRunRepository;
        this.suiteResultRepository = suiteResultRepository;
        this.caseResultRepository = caseResultRepository;
        this.stepResultRepository = stepResultRepository;
        this.verificationResultRepository = verificationResultRepository;
    }

    public record SuiteRunResult(
            UUID runId,
            String suiteName,
            String status,
            int totalCases,
            int passedCases,
            int failedCases,
            int errorCases,
            int skippedCases,
            long durationMs,
            List<CaseExecutionResult> caseResults
    ) {}

    public SuiteRunResult executeSuite(String suiteName, String dataFile) {
        var suite = loadSuite(suiteName);
        var dataRows = loadData(dataFile != null ? dataFile : suiteName);
        var suiteCtx = new SuiteContext(suite, appConfig);
        var startTime = Instant.now();

        var run = new TestRun(null, suite.suiteName(), suite.schemaVersion(),
                "RUNNING", startTime, null,
                dataRows.size(), 0, 0, 0, 0,
                null, suite.tags() != null ? tagListToMap(suite.tags()) : null,
                Map.of(), null);
        var runId = testRunRepository.insert(run);

        try {
            var beforeAllResults = lifecycleEngine.executeHooks(suite.beforeAll(), suiteCtx, null);

            List<CaseExecutionResult> caseResults = new ArrayList<>();
            int passed = 0, failed = 0, errored = 0, skipped = 0;

            for (var testCase : suite.cases()) {
                for (var dataRow : dataRows) {
                    var caseResult = caseRunner.executeCase(testCase, dataRow, suiteCtx);
                    caseResults.add(caseResult);

                    persistCaseResult(runId, caseResult);

                    switch (caseResult.status()) {
                        case "PASSED" -> passed++;
                        case "FAILED" -> failed++;
                        case "ERROR" -> errored++;
                        case "SKIPPED" -> skipped++;
                    }
                }
            }

            var afterAllResults = lifecycleEngine.executeHooks(suite.afterAll(), suiteCtx, null);

            var endTime = Instant.now();
            var durationMs = Duration.between(startTime, endTime).toMillis();
            var totalStatus = failed > 0 || errored > 0 ? "FAILED" : "PASSED";

            testRunRepository.updateStatus(runId, totalStatus, endTime, durationMs,
                    dataRows.size(), passed, failed, errored, skipped);

            persistSuiteResult(runId, suite, beforeAllResults, afterAllResults);

            return new SuiteRunResult(runId, suite.suiteName(), totalStatus,
                    dataRows.size(), passed, failed, errored, skipped, durationMs, caseResults);

        } catch (Exception e) {
            testRunRepository.updateStatus(runId, "ERROR", Instant.now(),
                    Duration.between(startTime, Instant.now()).toMillis(),
                    dataRows.size(), 0, 0, 0, 0);
            throw new RuntimeException("Suite execution failed: " + e.getMessage(), e);
        }
    }

    private void persistCaseResult(UUID runId, CaseExecutionResult caseResult) {
        var caseResultRecord = new TestCaseResult(null, runId, caseResult.caseName(),
                caseResult.status(), caseResult.dataRow(),
                null, null, caseResult.durationMs(),
                caseResult.totalSteps(), caseResult.passedSteps(), caseResult.failedSteps(),
                caseResult.errorMessage(), null);
        var caseResultId = caseResultRepository.insert(caseResultRecord);

        for (var entry : caseResult.stepResults().entrySet()) {
            var stepResult = entry.getValue();
            var stepResultRecord = new TestStepResult(null, caseResultId, entry.getKey(),
                    stepResult.status(), stepResult.failureCategory(),
                    stepResult.actionType(), stepResult.actionParams(), stepResult.response(),
                    null, null, stepResult.durationMs(),
                    stepResult.retryCount(), stepResult.errorMessage(), null);
            var stepResultId = stepResultRepository.insert(stepResultRecord);

            for (var vResult : stepResult.verificationResults()) {
                var vRecord = new VerificationResult(null, "step", stepResultId,
                        vResult.verifierType(), vResult.passed(),
                        vResult.actual(), vResult.expected(), vResult.message(), null);
                verificationResultRepository.insert(vRecord);
            }
        }
    }

    private void persistSuiteResult(UUID runId, TestSuite suite,
                                     List<LifecycleEngine.HookResult> beforeAll,
                                     List<LifecycleEngine.HookResult> afterAll) {
        var lifecycleResults = new LinkedHashMap<String, Object>();
        lifecycleResults.put("beforeAll", beforeAll.stream()
                .map(r -> Map.of("hookType", r.hookType(), "passed", r.passed(), "errors", r.errors()))
                .toList());
        lifecycleResults.put("afterAll", afterAll.stream()
                .map(r -> Map.of("hookType", r.hookType(), "passed", r.passed(), "errors", r.errors()))
                .toList());

        var snapshot = jsonMapper.convertValue(suite, Map.class);
        var suiteResult = new SuiteResult(null, runId, snapshot, lifecycleResults,
                "COMPLETED", 0, 0, 0, null, null, null, null);
        suiteResultRepository.insert(suiteResult);
    }

    private TestSuite loadSuite(String suiteName) {
        for (var dir : appConfig.getSuiteDirs()) {
            var file = new File(dir, suiteName.endsWith(".json") ? suiteName : suiteName + ".json");
            if (file.exists()) {
                try {
                    return jsonMapper.readValue(file, TestSuite.class);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to parse suite: " + file, e);
                }
            }
        }
        throw new RuntimeException("Suite not found: " + suiteName);
    }

    private List<Map<String, Object>> loadData(String dataRef) {
        for (var dir : appConfig.getDataDirs()) {
            var jsonFile = new File(dir, dataRef.endsWith(".json") ? dataRef : dataRef + ".json");
            if (jsonFile.exists()) {
                try {
                    return jsonMapper.readValue(jsonFile,
                            jsonMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
                } catch (IOException e) {
                    throw new RuntimeException("Failed to parse data file: " + jsonFile, e);
                }
            }
            var csvFile = new File(dir, dataRef.endsWith(".csv") ? dataRef : dataRef + ".csv");
            if (csvFile.exists()) {
                return loadCsv(csvFile);
            }
        }
        return List.of(Map.of());
    }

    private List<Map<String, Object>> loadCsv(File csvFile) {
        var results = new ArrayList<Map<String, Object>>();
        try (var scanner = new java.util.Scanner(csvFile)) {
            if (!scanner.hasNextLine()) return results;
            var headers = scanner.nextLine().split(",");
            while (scanner.hasNextLine()) {
                var values = scanner.nextLine().split(",", -1);
                var row = new LinkedHashMap<String, Object>();
                for (int i = 0; i < headers.length && i < values.length; i++) {
                    row.put(headers[i].trim(), values[i].trim());
                }
                results.add(row);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read CSV: " + csvFile, e);
        }
        return results;
    }

    private Map<String, Object> tagListToMap(List<String> tags) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < tags.size(); i++) {
            m.put(String.valueOf(i), tags.get(i));
        }
        return m;
    }
}

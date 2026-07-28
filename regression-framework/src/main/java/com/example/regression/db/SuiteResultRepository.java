package com.example.regression.db;

import com.example.regression.model.SuiteResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class SuiteResultRepository {

    private final JdbcTemplate jdbc;

    public SuiteResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SuiteResult result) {
        jdbc.update("""
                INSERT INTO suite_result (id, run_id, suite_snapshot, lifecycle_results, status,
                    total_steps, passed_steps, failed_steps, start_time)
                VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, now())
                """,
                UUID.randomUUID(), result.runId(),
                toJson(result.suiteSnapshot()), toJson(result.lifecycleResults()),
                result.status(), result.totalSteps(), result.passedSteps(), result.failedSteps());
    }

    private String toJson(Object obj) {
        try {
            var mapper = new com.fasterxml.jackson.databind.json.JsonMapper();
            return obj != null ? mapper.writeValueAsString(obj) : null;
        } catch (Exception e) { return null; }
    }
}

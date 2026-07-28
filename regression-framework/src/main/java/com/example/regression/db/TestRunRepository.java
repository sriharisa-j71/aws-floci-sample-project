package com.example.regression.db;

import com.example.regression.model.TestRun;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class TestRunRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    public TestRunRepository(JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public UUID insert(TestRun run) {
        var runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO test_run (run_id, suite_name, suite_version, status, start_time,
                    total_cases, passed_cases, failed_cases, error_cases, skipped_cases, tags, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """,
                runId, run.suiteName(), run.suiteVersion(), run.status(),
                Timestamp.from(run.startTime()),
                run.totalCases(), run.passedCases(), run.failedCases(),
                run.errorCases(), run.skippedCases(),
                toJson(run.tags()), toJson(run.metadata()));
        return runId;
    }

    public void updateStatus(UUID runId, String status, Instant endTime, long durationMs,
                             int totalCases, int passed, int failed, int errored, int skipped) {
        jdbc.update("""
                UPDATE test_run SET status = ?, end_time = ?, duration_ms = ?,
                    total_cases = ?, passed_cases = ?, failed_cases = ?,
                    error_cases = ?, skipped_cases = ?
                WHERE run_id = ?
                """,
                status, Timestamp.from(endTime), durationMs,
                totalCases, passed, failed, errored, skipped, runId);
    }

    public List<TestRun> listRecent(int limit) {
        return jdbc.query("SELECT * FROM test_run ORDER BY start_time DESC LIMIT ?",
                testRunRowMapper, limit);
    }

    public List<TestRun> listBySuite(String suiteName, int limit) {
        return jdbc.query("SELECT * FROM test_run WHERE suite_name = ? ORDER BY start_time DESC LIMIT ?",
                testRunRowMapper, suiteName, limit);
    }

    public TestRun findById(UUID runId) {
        return jdbc.queryForObject("SELECT * FROM test_run WHERE run_id = ?",
                testRunRowMapper, runId);
    }

    public List<TestRun> findStaleRuns() {
        return jdbc.query("SELECT * FROM test_run WHERE status = 'RUNNING'",
                testRunRowMapper);
    }

    private final RowMapper<TestRun> testRunRowMapper = (ResultSet rs, int rowNum) -> {
        var runId = rs.getObject("run_id", UUID.class);
        return new TestRun(
                runId,
                rs.getString("suite_name"),
                rs.getInt("suite_version"),
                rs.getString("status"),
                rs.getTimestamp("start_time").toInstant(),
                rs.getTimestamp("end_time") != null ? rs.getTimestamp("end_time").toInstant() : null,
                rs.getInt("total_cases"),
                rs.getInt("passed_cases"),
                rs.getInt("failed_cases"),
                rs.getInt("error_cases"),
                rs.getInt("skipped_cases"),
                rs.getLong("duration_ms"),
                parseJson(rs.getString("tags")),
                parseJson(rs.getString("metadata")),
                rs.getString("recovery_note")
        );
    };

    private String toJson(Object obj) {
        try { return obj != null ? jsonMapper.writeValueAsString(obj) : null; }
        catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null) return null;
        try { return jsonMapper.readValue(json, Map.class); }
        catch (Exception e) { return null; }
    }
}

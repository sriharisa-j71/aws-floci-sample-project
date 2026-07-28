package com.example.regression.db;

import com.example.regression.model.TestCaseResult;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class TestCaseResultRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    public TestCaseResultRepository(JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public UUID insert(TestCaseResult result) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO test_case_result (id, run_id, case_name, status, data_row,
                    start_time, duration_ms, total_steps, passed_steps, failed_steps, error_message)
                VALUES (?, ?, ?, ?, ?::jsonb, now(), ?, ?, ?, ?, ?)
                """,
                id, result.runId(), result.caseName(), result.status(),
                toJson(result.dataRow()), result.durationMs(),
                result.totalSteps(), result.passedSteps(), result.failedSteps(),
                result.errorMessage());
        return id;
    }

    public List<TestCaseResult> findByRunId(UUID runId) {
        return jdbc.query("SELECT * FROM test_case_result WHERE run_id = ? ORDER BY start_time",
                rowMapper, runId);
    }

    private final RowMapper<TestCaseResult> rowMapper = (ResultSet rs, int rowNum) -> {
        var id = rs.getObject("id", UUID.class);
        return new TestCaseResult(
                id,
                rs.getObject("run_id", UUID.class),
                rs.getString("case_name"),
                rs.getString("status"),
                parseJson(rs.getString("data_row")),
                rs.getTimestamp("start_time").toInstant(),
                rs.getTimestamp("end_time") != null ? rs.getTimestamp("end_time").toInstant() : null,
                rs.getLong("duration_ms"),
                rs.getInt("total_steps"),
                rs.getInt("passed_steps"),
                rs.getInt("failed_steps"),
                rs.getString("error_message"),
                parseJson(rs.getString("metadata"))
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

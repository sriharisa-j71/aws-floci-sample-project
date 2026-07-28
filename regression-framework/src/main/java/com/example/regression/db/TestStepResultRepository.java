package com.example.regression.db;

import com.example.regression.model.TestStepResult;
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
public class TestStepResultRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    public TestStepResultRepository(JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public UUID insert(TestStepResult result) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO test_step_result (id, case_result_id, step_name, status, failure_category,
                    action_type, action_params, response, start_time, duration_ms, retry_count, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, now(), ?, ?, ?)
                """,
                id, result.caseResultId(), result.stepName(), result.status(),
                result.failureCategory(), result.actionType(),
                toJson(result.actionParams()), toJson(result.response()),
                result.durationMs(), result.retryCount(), result.errorMessage());
        return id;
    }

    public List<TestStepResult> findByCaseResultId(UUID caseResultId) {
        return jdbc.query("SELECT * FROM test_step_result WHERE case_result_id = ? ORDER BY start_time",
                rowMapper, caseResultId);
    }

    private final RowMapper<TestStepResult> rowMapper = (ResultSet rs, int rowNum) -> {
        var id = rs.getObject("id", UUID.class);
        return new TestStepResult(
                id,
                rs.getObject("case_result_id", UUID.class),
                rs.getString("step_name"),
                rs.getString("status"),
                rs.getString("failure_category"),
                rs.getString("action_type"),
                parseJson(rs.getString("action_params")),
                parseJson(rs.getString("response")),
                rs.getTimestamp("start_time").toInstant(),
                rs.getTimestamp("end_time") != null ? rs.getTimestamp("end_time").toInstant() : null,
                rs.getLong("duration_ms"),
                rs.getInt("retry_count"),
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

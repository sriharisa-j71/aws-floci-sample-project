package com.example.regression.db;

import com.example.regression.model.VerificationResult;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class VerificationResultRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    public VerificationResultRepository(JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public void insert(VerificationResult result) {
        jdbc.update("""
                INSERT INTO verification_result (id, scope, scope_id, verifier_type, passed, actual, expected, message)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """,
                UUID.randomUUID(), result.scope(), result.scopeId(),
                result.verifierType(), result.passed(),
                toJson(result.actual()), toJson(result.expected()),
                result.message());
    }

    private String toJson(Object obj) {
        try { return obj != null ? jsonMapper.writeValueAsString(obj) : null; }
        catch (Exception e) { return null; }
    }
}

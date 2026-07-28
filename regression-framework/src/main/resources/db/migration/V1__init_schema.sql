CREATE TABLE regression_config (
    key         VARCHAR(255) PRIMARY KEY,
    value       TEXT NOT NULL,
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE test_run (
    run_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_name      VARCHAR(255) NOT NULL,
    suite_version   INTEGER NOT NULL DEFAULT 1,
    status          VARCHAR(20) NOT NULL,
    start_time      TIMESTAMP NOT NULL DEFAULT now(),
    end_time        TIMESTAMP,
    total_cases     INTEGER NOT NULL DEFAULT 0,
    passed_cases    INTEGER NOT NULL DEFAULT 0,
    failed_cases    INTEGER NOT NULL DEFAULT 0,
    error_cases     INTEGER NOT NULL DEFAULT 0,
    skipped_cases   INTEGER NOT NULL DEFAULT 0,
    duration_ms     BIGINT,
    tags            JSONB,
    metadata        JSONB,
    recovery_note   TEXT
);

CREATE TABLE suite_result (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id          UUID NOT NULL REFERENCES test_run(run_id) ON DELETE CASCADE,
    suite_snapshot  JSONB NOT NULL,
    lifecycle_results JSONB,
    status          VARCHAR(20) NOT NULL,
    total_steps     INTEGER NOT NULL DEFAULT 0,
    passed_steps    INTEGER NOT NULL DEFAULT 0,
    failed_steps    INTEGER NOT NULL DEFAULT 0,
    start_time      TIMESTAMP NOT NULL DEFAULT now(),
    end_time        TIMESTAMP,
    duration_ms     BIGINT,
    error_message   TEXT
);

CREATE TABLE verification_result (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope           VARCHAR(20) NOT NULL,
    scope_id        UUID NOT NULL,
    verifier_type   VARCHAR(100) NOT NULL,
    passed          BOOLEAN NOT NULL,
    actual          JSONB,
    expected        JSONB,
    message         TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE test_case_result (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id          UUID NOT NULL REFERENCES test_run(run_id) ON DELETE CASCADE,
    case_name       VARCHAR(255) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    data_row        JSONB,
    start_time      TIMESTAMP NOT NULL DEFAULT now(),
    end_time        TIMESTAMP,
    duration_ms     BIGINT,
    total_steps     INTEGER NOT NULL DEFAULT 0,
    passed_steps    INTEGER NOT NULL DEFAULT 0,
    failed_steps    INTEGER NOT NULL DEFAULT 0,
    error_message   TEXT,
    metadata        JSONB
);

CREATE TABLE test_step_result (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_result_id  UUID NOT NULL REFERENCES test_case_result(id) ON DELETE CASCADE,
    step_name       VARCHAR(255) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    failure_category VARCHAR(20),
    action_type     VARCHAR(100) NOT NULL,
    action_params   JSONB,
    response        JSONB,
    start_time      TIMESTAMP NOT NULL DEFAULT now(),
    end_time        TIMESTAMP,
    duration_ms     BIGINT,
    retry_count     INTEGER NOT NULL DEFAULT 0,
    error_message   TEXT,
    metadata        JSONB
);

CREATE INDEX idx_test_run_status ON test_run(status);
CREATE INDEX idx_test_run_suite ON test_run(suite_name);
CREATE INDEX idx_suite_result_run ON suite_result(run_id);
CREATE INDEX idx_test_case_run ON test_case_result(run_id);
CREATE INDEX idx_test_step_case ON test_step_result(case_result_id);
CREATE INDEX idx_verification_scope ON verification_result(scope, scope_id);
CREATE INDEX idx_verification_passed ON verification_result(scope, passed);

CREATE INDEX idx_test_run_tags ON test_run USING GIN (tags);
CREATE INDEX idx_test_run_metadata ON test_run USING GIN (metadata);
CREATE INDEX idx_case_data_row ON test_case_result USING GIN (data_row);
CREATE INDEX idx_step_response ON test_step_result USING GIN (response);
CREATE INDEX idx_verification_actual ON verification_result USING GIN (actual);
CREATE INDEX idx_verification_expected ON verification_result USING GIN (expected);

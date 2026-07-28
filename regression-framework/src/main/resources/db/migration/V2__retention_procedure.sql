CREATE TABLE test_run_archive (LIKE test_run INCLUDING ALL);
CREATE TABLE suite_result_archive (LIKE suite_result INCLUDING ALL);
CREATE TABLE test_case_result_archive (LIKE test_case_result INCLUDING ALL);
CREATE TABLE test_step_result_archive (LIKE test_step_result INCLUDING ALL);
CREATE TABLE verification_result_archive (LIKE verification_result INCLUDING ALL);

CREATE PROCEDURE archive_runs(older_than_days INTEGER DEFAULT 90)
LANGUAGE plpgsql AS $$
DECLARE
    archive_cutoff TIMESTAMP;
    archived_count INTEGER;
BEGIN
    archive_cutoff := now() - (older_than_days || ' days')::INTERVAL;

    INSERT INTO verification_result_archive
    SELECT vr.* FROM verification_result vr
    JOIN test_step_result tsr ON vr.scope_id = tsr.id AND vr.scope = 'step'
    JOIN test_case_result tcr ON tsr.case_result_id = tcr.id
    JOIN test_run tr ON tcr.run_id = tr.run_id
    WHERE tr.start_time < archive_cutoff;

    INSERT INTO test_step_result_archive
    SELECT tsr.* FROM test_step_result tsr
    JOIN test_case_result tcr ON tsr.case_result_id = tcr.id
    JOIN test_run tr ON tcr.run_id = tr.run_id
    WHERE tr.start_time < archive_cutoff;

    INSERT INTO test_case_result_archive
    SELECT tcr.* FROM test_case_result tcr
    JOIN test_run tr ON tcr.run_id = tr.run_id
    WHERE tr.start_time < archive_cutoff;

    INSERT INTO suite_result_archive
    SELECT sr.* FROM suite_result sr
    JOIN test_run tr ON sr.run_id = tr.run_id
    WHERE tr.start_time < archive_cutoff;

    INSERT INTO test_run_archive
    SELECT * FROM test_run WHERE start_time < archive_cutoff;

    DELETE FROM verification_result WHERE scope_id IN (
        SELECT tsr.id FROM test_step_result tsr
        JOIN test_case_result tcr ON tsr.case_result_id = tcr.id
        JOIN test_run tr ON tcr.run_id = tr.run_id
        WHERE tr.start_time < archive_cutoff
    );

    DELETE FROM test_step_result WHERE case_result_id IN (
        SELECT tcr.id FROM test_case_result tcr
        JOIN test_run tr ON tcr.run_id = tr.run_id
        WHERE tr.start_time < archive_cutoff
    );

    DELETE FROM test_case_result WHERE run_id IN (
        SELECT run_id FROM test_run WHERE start_time < archive_cutoff
    );

    DELETE FROM suite_result WHERE run_id IN (
        SELECT run_id FROM test_run WHERE start_time < archive_cutoff
    );

    DELETE FROM test_run WHERE start_time < archive_cutoff;

    GET DIAGNOSTICS archived_count = ROW_COUNT;
    RAISE NOTICE 'Archived % runs older than % days', archived_count, older_than_days;
END;
$$;

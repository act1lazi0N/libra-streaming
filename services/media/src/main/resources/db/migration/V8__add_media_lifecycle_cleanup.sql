-- M31: the durable state behind upload expiry, scoped object cleanup and the operator status. Errors use SQLSTATE
-- 23000 like the M24-M26 guards.

-- The stage a job was in when its last failure was recorded, so the status can say where a retry or a terminal
-- failure happened. Rows written before M31 keep a code without a stage.
ALTER TABLE media_jobs ADD COLUMN failure_stage VARCHAR(16)
    CONSTRAINT ck_media_jobs_failure_stage
        CHECK (failure_stage IN ('CLAIMED', 'SOURCE_SELECTED', 'TRANSCODING', 'FINALIZING'));
ALTER TABLE media_jobs ADD CONSTRAINT ck_media_jobs_failure_stage_with_code
    CHECK (failure_stage IS NULL OR failure_code IS NOT NULL);

-- Set once every object the upload's attempts could have written, other than its selected source and output, has
-- been deleted. Cleanup never touches a row that has it.
ALTER TABLE media_uploads ADD COLUMN objects_cleaned_at TIMESTAMPTZ;
-- When a pass last took the upload; uploads are taken least recently first, so one that keeps failing goes to the
-- back of the queue instead of holding the front of every batch.
ALTER TABLE media_uploads ADD COLUMN cleanup_attempted_at TIMESTAMPTZ;
CREATE INDEX ix_media_uploads_cleanup ON media_uploads(cleanup_attempted_at NULLS FIRST, expires_at, id)
    WHERE objects_cleaned_at IS NULL AND state <> 'OPEN';

-- A terminal job is final: cleanup decides from it that no lease can still select or write an attempt, so its
-- stage, attempt count, lease and failure can never change again.
CREATE FUNCTION media_jobs_guard_terminal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.stage IN ('SUCCEEDED', 'FAILED_PERMANENT', 'EXHAUSTED')
            AND (NEW.stage, NEW.attempt_count, NEW.lease_token, NEW.lease_until, NEW.failure_code,
                NEW.failure_stage, NEW.upload_id)
            IS DISTINCT FROM (OLD.stage, OLD.attempt_count, OLD.lease_token, OLD.lease_until, OLD.failure_code,
                OLD.failure_stage, OLD.upload_id) THEN
        RAISE EXCEPTION 'media job terminal stage is final' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER media_jobs_guard_terminal BEFORE UPDATE ON media_jobs
    FOR EACH ROW EXECUTE FUNCTION media_jobs_guard_terminal();

-- An expired upload stays expired, and the cleanup mark is written once, only for an upload whose objects can no
-- longer be selected: an expired upload (no job, nothing selectable) or a submitted one whose job is terminal.
CREATE FUNCTION media_uploads_guard_lifecycle() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state = 'EXPIRED' AND NEW.state <> 'EXPIRED' THEN
        RAISE EXCEPTION 'media upload EXPIRED is terminal' USING ERRCODE = '23000';
    END IF;
    IF OLD.objects_cleaned_at IS NOT NULL AND NEW.objects_cleaned_at IS DISTINCT FROM OLD.objects_cleaned_at THEN
        RAISE EXCEPTION 'media upload cleanup is recorded once' USING ERRCODE = '23000';
    END IF;
    IF OLD.objects_cleaned_at IS NULL AND NEW.objects_cleaned_at IS NOT NULL
            AND NOT (NEW.state = 'EXPIRED'
                OR (NEW.state = 'SUBMITTED' AND EXISTS (
                    SELECT 1 FROM media_jobs j WHERE j.upload_id = NEW.id
                        AND j.stage IN ('SUCCEEDED', 'FAILED_PERMANENT', 'EXHAUSTED')))) THEN
        RAISE EXCEPTION 'media upload objects are still selectable' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER media_uploads_guard_lifecycle BEFORE UPDATE ON media_uploads
    FOR EACH ROW EXECUTE FUNCTION media_uploads_guard_lifecycle();

-- An expired upload's asset fails with it, in the same transaction, and never had a job.
CREATE FUNCTION media_uploads_require_expiry_failure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM media_uploads u
            JOIN media_assets a ON a.asset_id = u.asset_id AND a.asset_version = u.asset_version
            WHERE u.id = NEW.id AND u.state = 'EXPIRED' AND a.state = 'FAILED')
            OR EXISTS (SELECT 1 FROM media_jobs j WHERE j.upload_id = NEW.id) THEN
        RAISE EXCEPTION 'media upload expiry requires a failed asset without a job' USING ERRCODE = '23000';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER media_uploads_require_expiry_failure AFTER UPDATE ON media_uploads
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.state = 'EXPIRED' AND OLD.state <> 'EXPIRED')
    EXECUTE FUNCTION media_uploads_require_expiry_failure();

-- Operator gauges read these without scanning terminal history.
CREATE INDEX ix_media_jobs_stage ON media_jobs(stage, failure_code);

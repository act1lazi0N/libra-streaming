-- M24: the database's own guard for the READY selection. The application already fences, orders and commits these
-- writes together; these triggers refuse any other path (a future bug, a manual statement) that would change a
-- selected source or output generation, leave READY, or commit READY without the event that announces it.

CREATE FUNCTION media_assets_guard_selection() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.selected_source_key IS NOT NULL
            AND NEW.selected_source_key IS DISTINCT FROM OLD.selected_source_key THEN
        RAISE EXCEPTION 'media selected source is immutable' USING ERRCODE = '23000';
    END IF;
    IF OLD.output_prefix IS NOT NULL AND (NEW.output_prefix IS DISTINCT FROM OLD.output_prefix
            OR NEW.master_manifest_key IS DISTINCT FROM OLD.master_manifest_key
            OR NEW.duration_seconds IS DISTINCT FROM OLD.duration_seconds) THEN
        RAISE EXCEPTION 'media selected output is immutable' USING ERRCODE = '23000';
    END IF;
    -- Replacing a READY asset is outside the first slice; READY is terminal for an asset version.
    IF OLD.state = 'READY' AND NEW.state <> 'READY' THEN
        RAISE EXCEPTION 'media READY is terminal' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER media_assets_guard_selection BEFORE UPDATE ON media_assets
    FOR EACH ROW EXECUTE FUNCTION media_assets_guard_selection();

-- Checked at commit, on the row as committed: a READY asset must have the READY event of its current aggregate
-- version, carrying its duration. The transition, the version bump and the outbox insert are separate statements of
-- one transaction, so only a deferred check sees them together.
CREATE FUNCTION media_assets_require_ready_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    committed media_assets%ROWTYPE;
BEGIN
    SELECT * INTO committed FROM media_assets
    WHERE asset_id = NEW.asset_id AND asset_version = NEW.asset_version;
    IF FOUND AND committed.state = 'READY' AND NOT EXISTS (
            SELECT 1 FROM media_outbox_events e
            WHERE e.asset_id = committed.asset_id AND e.asset_version = committed.asset_version
                AND e.aggregate_version = committed.aggregate_version
                AND e.payload->>'state' = 'READY'
                AND e.payload->'durationSeconds' = to_jsonb(committed.duration_seconds)) THEN
        RAISE EXCEPTION 'media READY requires its event' USING ERRCODE = '23000';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER media_assets_require_ready_event AFTER INSERT OR UPDATE ON media_assets
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.state = 'READY') EXECUTE FUNCTION media_assets_require_ready_event();

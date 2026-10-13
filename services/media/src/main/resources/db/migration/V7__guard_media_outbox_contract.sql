-- M26: an outbox event must be exactly the contract event of the transition it announces, and a failed delivery
-- records why it failed. Errors use SQLSTATE 23000 (trigger) or 23514 (check), both integrity violations.

ALTER TABLE media_outbox_events ADD COLUMN last_error_code VARCHAR(32)
    CONSTRAINT ck_media_outbox_error_code CHECK (last_error_code IN ('BROKER_UNAVAILABLE', 'BROKER_REJECTED'));
ALTER TABLE media_outbox_events ADD CONSTRAINT ck_media_outbox_sent_without_error
    CHECK (delivery_state <> 'SENT' OR last_error_code IS NULL);

-- The payload carries exactly the contract keys, with the row's own asset and asset version, a contract state, and
-- a duration for READY only. CASE keeps each cast behind its type test.
ALTER TABLE media_outbox_events ADD CONSTRAINT ck_media_outbox_payload_contract CHECK (
    payload ?& ARRAY['contentId', 'bindingId', 'assetId', 'assetVersion', 'state']
    AND payload - ARRAY['contentId', 'bindingId', 'assetId', 'assetVersion', 'state', 'durationSeconds']
        = '{}'::jsonb
    AND payload -> 'assetId' = to_jsonb(asset_id::text)
    AND payload -> 'assetVersion' = to_jsonb(asset_version)
    AND payload ->> 'state' IN ('PROCESSING', 'READY', 'FAILED')
    AND jsonb_typeof(payload -> 'state') = 'string'
    AND jsonb_typeof(payload -> 'contentId') = 'string'
    AND jsonb_typeof(payload -> 'bindingId') = 'string'
    AND (payload ->> 'state' = 'READY') = (payload ? 'durationSeconds')
    AND CASE WHEN NOT payload ? 'durationSeconds' THEN true
        WHEN jsonb_typeof(payload -> 'durationSeconds') = 'number'
            AND payload ->> 'durationSeconds' ~ '^[1-9][0-9]{0,2}$'
            THEN (payload ->> 'durationSeconds')::integer BETWEEN 1 AND 600
        ELSE false END);

-- Checked against the asset as the transition left it in the same transaction: the event names the asset's binding
-- and content, has the asset's new aggregate version and state (and its duration), carries the upload's request ID,
-- and starts undelivered.
CREATE FUNCTION media_outbox_require_transition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    asset media_assets%ROWTYPE;
    request UUID;
BEGIN
    SELECT a.* INTO asset FROM media_assets a WHERE a.asset_id = NEW.asset_id AND a.asset_version = NEW.asset_version;
    SELECT u.request_id INTO request FROM media_uploads u
    WHERE u.asset_id = NEW.asset_id AND u.asset_version = NEW.asset_version;
    IF asset.asset_id IS NULL
            OR NEW.payload -> 'contentId' IS DISTINCT FROM to_jsonb(asset.content_id::text)
            OR NEW.payload -> 'bindingId' IS DISTINCT FROM to_jsonb(asset.binding_id::text)
            OR NEW.aggregate_version IS DISTINCT FROM asset.aggregate_version
            OR NEW.payload ->> 'state' IS DISTINCT FROM asset.state
            OR (NEW.payload ? 'durationSeconds'
                AND NEW.payload -> 'durationSeconds' IS DISTINCT FROM to_jsonb(asset.duration_seconds))
            OR request IS NULL OR NEW.correlation_id IS DISTINCT FROM request THEN
        RAISE EXCEPTION 'media outbox event contradicts its asset' USING ERRCODE = '23000';
    END IF;
    IF NEW.delivery_state <> 'PENDING' OR NEW.lease_token IS NOT NULL OR NEW.attempt_count <> 0
            OR NEW.sent_at IS NOT NULL OR NEW.last_error_code IS NOT NULL THEN
        RAISE EXCEPTION 'media outbox events start pending' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER media_outbox_require_transition BEFORE INSERT ON media_outbox_events
    FOR EACH ROW EXECUTE FUNCTION media_outbox_require_transition();

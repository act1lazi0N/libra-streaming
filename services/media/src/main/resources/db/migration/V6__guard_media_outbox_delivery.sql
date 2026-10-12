-- M25: the database's own guard for outbox delivery. The relay only leases, acknowledges and returns events; these
-- rules refuse any other path that would rewrite an event, delete it, revive a SENT one, or mark an event SENT
-- while an earlier version of its asset is still unsent. Errors use SQLSTATE 23000 like the M24 guards.

CREATE FUNCTION media_outbox_guard_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'media outbox events are retained' USING ERRCODE = '23000';
    END IF;
    IF (NEW.event_id, NEW.asset_id, NEW.asset_version, NEW.aggregate_version, NEW.topic, NEW.event_type,
            NEW.schema_version, NEW.correlation_id, NEW.payload, NEW.created_at)
            IS DISTINCT FROM (OLD.event_id, OLD.asset_id, OLD.asset_version, OLD.aggregate_version, OLD.topic,
            OLD.event_type, OLD.schema_version, OLD.correlation_id, OLD.payload, OLD.created_at) THEN
        RAISE EXCEPTION 'media outbox event is immutable' USING ERRCODE = '23000';
    END IF;
    IF OLD.delivery_state = 'SENT' THEN
        RAISE EXCEPTION 'media outbox SENT is terminal' USING ERRCODE = '23000';
    END IF;
    IF NEW.attempt_count < OLD.attempt_count THEN
        RAISE EXCEPTION 'media outbox attempts only grow' USING ERRCODE = '23000';
    END IF;
    -- The SENT events of an asset are always a prefix of its versions.
    IF NEW.delivery_state = 'SENT' AND EXISTS (
            SELECT 1 FROM media_outbox_events older
            WHERE older.asset_id = NEW.asset_id AND older.aggregate_version < NEW.aggregate_version
                AND older.delivery_state <> 'SENT') THEN
        RAISE EXCEPTION 'media outbox events are sent in version order' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER media_outbox_guard_delivery BEFORE UPDATE OR DELETE ON media_outbox_events
    FOR EACH ROW EXECUTE FUNCTION media_outbox_guard_delivery();

-- Expired leases are claimable too; the M01 partial index covers PENDING only.
CREATE INDEX ix_media_outbox_leased ON media_outbox_events(lease_until) WHERE delivery_state = 'LEASED';

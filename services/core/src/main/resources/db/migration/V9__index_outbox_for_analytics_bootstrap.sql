-- RA02.4: Analytics bootstrap reads (core-internal-analytics.v1) page through the outbox. Index only; no data change.
CREATE INDEX ix_outbox_replay ON outbox_events (topic, created_at, event_id);
CREATE INDEX ix_outbox_profile_tombstones ON outbox_events (aggregate_id)
    WHERE topic = 'core.profiles.v1' AND event_type = 'ProfileDeleted';

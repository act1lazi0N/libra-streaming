ALTER TABLE media_jobs ADD CONSTRAINT ck_media_jobs_active_lease
    CHECK ((stage IN ('CLAIMED', 'SOURCE_SELECTED', 'TRANSCODING', 'FINALIZING')) = (lease_token IS NOT NULL));

ALTER TABLE media_outbox_events ADD CONSTRAINT ck_media_outbox_delivery_lease
    CHECK ((delivery_state = 'LEASED') = (lease_token IS NOT NULL));

ALTER TABLE media_assets ADD CONSTRAINT ck_media_master_under_output
    CHECK (master_manifest_key IS NULL OR
        (output_prefix IS NOT NULL AND
            left(master_manifest_key, length(output_prefix) + 1) = output_prefix || '/'));

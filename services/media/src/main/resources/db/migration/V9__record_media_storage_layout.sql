-- M32: the storage layout every object key is derived from. Only an upload's selected source and output are recorded
-- with it; every other key an attempt reserved (and cleanup deletes) is named from the buckets and prefixes. The first
-- instance records the layout, and an instance configured with another one refuses to start, so no worker writes
-- where cleanup does not look and cleanup never records an upload clean after looking in the wrong place.
-- Once any upload exists the layout is fixed: the selections it recorded are immutable (V5), so objects cannot be moved
-- under another layout in place. Before the first upload, a mistyped first layout is corrected by deleting the row.
CREATE TABLE media_storage_layout (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CONSTRAINT ck_media_storage_layout_singleton CHECK (singleton),
    source_bucket VARCHAR(63) NOT NULL,
    hls_bucket VARCHAR(63) NOT NULL,
    staging_prefix VARCHAR(255) NOT NULL,
    source_prefix VARCHAR(255) NOT NULL,
    hls_prefix VARCHAR(255) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION media_storage_layout_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM media_uploads) THEN
        RAISE EXCEPTION 'media storage layout is fixed once an upload exists' USING ERRCODE = '23000';
    END IF;
    RETURN COALESCE(NEW, OLD);
END $$;

CREATE TRIGGER media_storage_layout_guard BEFORE UPDATE OR DELETE ON media_storage_layout
    FOR EACH ROW EXECUTE FUNCTION media_storage_layout_guard();

-- Validated technical facts about the selected frozen source. A row exists only for a source that passed the
-- first-slice policy, so the checks repeat that policy as a last line of defence. The container is always MP4
-- and the video codec always H.264, so neither is stored.
CREATE TABLE media_source_metadata (
    asset_id UUID NOT NULL,
    asset_version BIGINT NOT NULL,
    source_key VARCHAR(512) NOT NULL CHECK (length(trim(source_key)) > 0),
    duration_millis INTEGER NOT NULL CHECK (duration_millis BETWEEN 1 AND 600000),
    video_profile VARCHAR(24) NOT NULL
        CHECK (video_profile IN ('Baseline', 'Constrained Baseline', 'Main', 'High')),
    coded_width INTEGER NOT NULL,
    coded_height INTEGER NOT NULL,
    rotation SMALLINT NOT NULL CHECK (rotation IN (0, 90, 180, 270)),
    display_width INTEGER NOT NULL,
    display_height INTEGER NOT NULL,
    frame_rate_numerator INTEGER NOT NULL CHECK (frame_rate_numerator > 0),
    frame_rate_denominator INTEGER NOT NULL CHECK (frame_rate_denominator > 0),
    audio_channels SMALLINT,
    audio_sample_rate INTEGER,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (asset_id, asset_version),
    FOREIGN KEY (asset_id, asset_version) REFERENCES media_assets(asset_id, asset_version),
    CHECK (LEAST(coded_width, coded_height) >= 16 AND GREATEST(coded_width, coded_height) <= 1920
        AND LEAST(coded_width, coded_height) <= 1080),
    CHECK (LEAST(display_width, display_height) >= 16 AND GREATEST(display_width, display_height) <= 1920
        AND LEAST(display_width, display_height) <= 1080),
    CHECK (frame_rate_numerator::BIGINT >= frame_rate_denominator
        AND frame_rate_numerator::BIGINT <= 30::BIGINT * frame_rate_denominator),
    CHECK ((audio_channels IS NULL) = (audio_sample_rate IS NULL)),
    CHECK (audio_channels IS NULL OR (audio_channels BETWEEN 1 AND 8 AND audio_sample_rate BETWEEN 8000 AND 96000))
);

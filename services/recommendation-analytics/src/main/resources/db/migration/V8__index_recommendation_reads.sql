-- RA05.2: indexes for candidate ranking reads (docs/analytics-ranking-ra05-2.md). No data changes.

-- Popularity: sessions that qualified in the last 7 days.
CREATE INDEX ix_playback_sessions_qualified_at ON playback_sessions (qualified_at) WHERE qualified_at IS NOT NULL;

-- Candidate titles: published movies and series.
CREATE INDEX ix_catalog_nodes_published_titles ON catalog_nodes (kind)
    WHERE kind IN ('MOVIE', 'SERIES') AND published IS NOT NULL;

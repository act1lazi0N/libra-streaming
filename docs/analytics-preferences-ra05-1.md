# Analytics: Genre Preferences (Milestone RA05.1)

Verification date: 2026-11-18.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA05.1 builds genre interests per profile from accepted watched time in the last 30 days. Accepted watched time is
the sum of `acceptedWatchedMs` of the playback facts of [RA02.2](analytics-playback-facts-ra02-2.md). The interests
feed the candidate ranking of [RA05.2](analytics-ranking-ra05-2.md). Nothing new is served on its own.

Done condition (plan): a content item watched repeatedly cannot gain unlimited influence, and each profile has its
own preference data.

Contract and migration changes:

- No contract changed.
- New migration `V7__create_profile_watch_days.sql`: table `profile_watch_days` and a one-time backfill from the
  linked playback facts stored before it.
- `PlaybackStore.append` now returns `StoredFact` (the profile link it kept plus the qualification change), so the
  projection writes the rollup under the same profile-link decision as the fact.

## Design

**What is stored.** `profile_watch_days(profile_id, content_id, day, account_id, accepted_watched_ms)`, primary key
`(profile_id, content_id, day)`. `content_id` is the item that was played (a movie or an episode). The rollup does
not store its movie or series because an episode may arrive before its season and series (Core gives no order across
aggregates). The title is resolved from the catalog projection when interests are read. Like the statistics, `day`
is the UTC date of the fact's stored instant (microseconds), so a fact and its watched time never fall on different
days. Rows hold accepted increments only; a fact with `acceptedWatchedMs = 0` (seeking, pausing) writes nothing.

**Same transaction, same profile decision.** `PlaybackProjection.apply` stores the fact, updates the statistics and
then, only if the fact kept its profile link, adds to `profile_watch_days`. This all happens in the intake
transaction that also records the receipt. A redelivered event is stopped by the receipt and adds nothing. The link
was decided by `JdbcPlaybackStore` under the profile's shared advisory lock, which the transaction still holds.
A tombstone committed earlier left no link, so nothing is written. A tombstone committing later takes the exclusive
lock after this commit and erases the rows. `JdbcPreferenceStore` is a `ProfileErasure`: `ProfileDeleted` deletes
every row of the profile in the tombstone's transaction. This is personalization, unlike the statistics rollup,
which keeps aggregate history (RA01.1).

**Account scoping.** Each row keeps the account of its first writer, and an increment from another account does not
add to it. Every read filters on both the profile and the account in the query. A request that pairs a profile with
the wrong account gets no interests. Core never moves a profile between accounts, so this is a guard, not a rule
that normal data reaches.

**Window.** Interests use the rows of the last 30 UTC days, today included, by the Analytics clock
(`InterestRules.windowDays`). The rollup keeps older rows; they stop counting as days pass. A retention purge is
RA09.2, as for every Analytics table.

**From watched time to interests** (`GenreInterests.of`, pure domain code):

| Step | Rule | Why |
| --- | --- | --- |
| 1. Item cap | An item counts at most its own current duration (`duration_seconds` of its active binding). Without a known duration it counts at most 2 h, and never more than 4 h. | Rewatching a movie or an episode adds nothing beyond one full watch. |
| 2. Title | Items are grouped into their movie or series (episode → season → series). An item whose chain is unknown counts nowhere. | A series is one interest, however many episodes were played. |
| 3. Title cap | A movie or series counts at most 6 h in total. | One long binge cannot outweigh every other title. |
| 4. Genre split | A title's weight is split equally among its distinct genres. A title without genres (or currently unpublished, whose genres are empty) counts nowhere. | A title tagged with five genres contributes as much in total as a title with one. |
| 5. Shares | Each genre's weight divided by the sum of all weights. | Ranking compares relative interest, not hours. |

The caps are the defaults of `InterestRules.DEFAULT` (30 days, 2 h / 4 h / 6 h). Genres are summed in sorted order,
so equal inputs give bit-identical shares and ties in the ranking stay ties.

**Catalog changes after viewing.** Genres and durations are read from the current catalog projection. If an admin
changes a title's genres, past watching counts towards the new genres. If a title is unpublished, its watching stops
counting until it is published again. This follows Core, which owns the catalog. Analytics does not keep a frozen
copy of what was shown at the time.

## Verification

See [RA05.2](analytics-ranking-ra05-2.md#verification) for the shared run and counts.

| Proof | Suite |
| --- | --- |
| Item cap at duration, 2 h unknown cap, 4 h item cap, 6 h title cap across episodes | `GenreInterestsTest` |
| Equal split over distinct genres, unattributed time ignored, shares sum to 1, order-independent bit-equal weights | `GenreInterestsTest` |
| UTC day of a fact at 23:59:59.999999Z, zero increments skipped, window from `today − 29` | `ProfilePreferencesTest` |
| Rows per profile, item and day through the real intake; duplicates add nothing; profiles kept apart | `PreferenceRollupIntegrationTest` |
| Wrong account reads nothing; tombstone erases; later facts for a deleted profile add nothing | `PreferenceRollupIntegrationTest` |
| Episode resolved to its series' genres; unknown chain and unpublished titles count nowhere; window boundary | `PreferenceRollupIntegrationTest` |
| V7 backfill equals the live rollup and skips unlinked facts | `PreferenceRollupIntegrationTest` |
| The new table is part of the order-independence and fault-injection convergence dumps | `OrderIndependenceIntegrationTest`, `FaultInjectionIntegrationTest` |
| A fact racing a tombstone leaves no rollup row in either commit order | `ProfileErasureRaceIntegrationTest` |
| Repeat viewing bounded through the real database: a 1-minute movie watched 10 minutes counts 60 s | `RecommendationsApiIntegrationTest` |

## Limits

- Interests are computed per request from at most 30 days × the items a profile played. They are not cached.
  RA05.4 measures the 500 ms budget under load, and RA09.3 bounds it.
- Deleting history in Core does not reset interests or completion in Analytics. The plan states this explicitly:
  deleting history is not a preference reset. No event carries a history deletion.
- Durations come from the item's current active binding. A replaced asset with another length changes the cap of
  past watching. Asset version per session is RA07.1.
- No time decay inside the window: a day 29 days ago weighs as much as today.
- **Deployment order.** The V7 backfill runs once. A pre-RA05.1 instance that keeps consuming `core.playback.v1`
  after V7 ran would commit facts without their rollup rows, and nothing adds them later. Stop every playback
  consumer of the old version before migrating, as for V6 (RA04.1). The same holds for any later rollup.
- The backfill keeps the account of each profile's earliest fact; the live path keeps the first writer of each row.
  They are equal while a profile belongs to one account, which Core guarantees.
- Local evidence only; Analytics and Core were not run together (RA09).

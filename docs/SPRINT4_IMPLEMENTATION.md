# Sprint 4 Kotlin — implementation handoff (not final grading evidence)

This document describes the code in `feature/sprint4-kotlin-discovery` for review by
the Kotlin subgroup. It does not claim a physical-device test, deployed pipeline
or finished profiling. The team owns creating issues, milestones, PRs, reviews
and the final merge. Do **not** use this draft as retrospective evidence of
individual contributions.

## New views and features (proposed ownership, not assigned authorship)

| Proposed owner | New Kotlin view | New feature | Proposed individual BQ |
| --- | --- | --- | --- |
| Julián Ramírez | Instant Plan | Direct one-tap start from Explore; save one feasible plan privately per account | BQ4 (Type 2) |
| Lex Betancourt | Explore Together | Browse group-labelled quests fitting time and budget | BQ7 (Type 3) |
| Sebastián Maldonado | Something Different | View a category-diverse local shortlist; expose actual server BQ8 experiment metadata for Smart Picks | BQ8 (Type 3) |
| Carlos Poveda | Social Comfort | Stable per-account control/personalized comparison of social comfort filtering without changing profile | BQ14 (Type 5) |

**Historical attribution:** Sebastián's Sprint 2 BQ was BQ5 and Sprint 3
BQ was BQ9. BQ12 is NOT a completed Sebastián Sprint 3 BQ. The unresolved
Backend PR #5 and withdrawn Analytics PR #8 are not integrated here.

**Eligibility caveat:** The original Sprint 1 list includes BQ4/BQ7/BQ8/BQ14,
but some BQs already appeared in the Flutter subgroup. Before claiming Sprint 4
credit confirm with the professor that cross-platform reuse is permitted in
Sprint 4 as it was in Sprint 3. Code reuse is not evidence of academic approval.

## Data ownership and exact boundaries

- Source catalogue: shared Supabase `quests` and `quest_steps` read through
  `SidequestsRepository`. The views may show a previously saved JSON catalogue;
  its source label is retained. The screens do not invent quest results.
- BQ4: the app emits `s4_start_confirmed` and a `quest_accepted` event only
  after the backend confirms a new attempt. Path, elapsed seconds, interaction
  count and attempt ID are recorded. Start elapsed time is monotonic and resets
  at Explorer entry. These are descriptive observations, not randomized data.
  The Analytics Sprint 4 branch deduplicates attempts across event types.
- BQ7: `isGroup` is only a classification of quests. The current backend does
  not represent actual challenge membership; the BQ7 shared query measures
  attempts by group-labelled vs individual quests, not participants.
- BQ8: the `recommend_quests` RPC already assigns a user-stable control/diverse
  variant. Kotlin now decodes `variant`/`rank_position` and sends one batch ID
  per fresh response. Cached responses are not new impressions. The local
  "Something Different" screen does *not* impersonate the server A/B arm.
- BQ14: the app assigns an account-stable variant; the personalized arm filters
  the same eligible catalogue using the user's temporary comfort choice while
  the control does not. Events include variant/comfort. The shared Analytics
  branch computes observed acceptance, completion and seven-day-return rates;
  cohorts without seven full days are excluded from the return denominator.
  Balanced cohorts and live validation remain mandatory before conclusions.

## Concurrency, local storage, cache and eventual connectivity

- **Concurrency:** `AppViewModel.sprint4LoadPinned` runs account-scoped local
  retrieval in a coroutine while the catalogue loads independently. Storage
  I/O executes on `Dispatchers.IO`, not the UI thread. Navigation is not
  blocked by remote loading. No new worker or thread pool was introduced.
- **Local storage:** `SharedPreferencesSprint4PlanStore` keeps one selected
  quest ID in private storage using an account-scoped key. It is not a backend
  write and intentionally needs no synchronization.
- **Caching:** the existing persisted catalogue is the offline data source for
  all four new views, and the existing Smart Picks LRU cache remains behind
  the BQ8-enabled RPC. The new algorithms do not introduce a redundant cache.
- **Eventual connectivity:** views and local planning remain usable with a
  cached catalogue; starting a new quest remains server-authoritative and
  fails clearly when offline. New discovery/confirmed-start/acceptance events
  use the existing durable `LexLocalStore` + WorkManager queue, retaining
  timestamps. Do not claim an event was uploaded until observed in Supabase.

The four screens are NOT a substitute for platform parity: the Flutter subgroup
must explicitly review and implement equivalent UI/behavior before final delivery.

## Validation gates

1. Kotlin: `:app:testDebugUnitTest` and `:app:assembleDebug` pass on the
   latest commit of this branch. Check Android CI, do not rely on old green runs.
2. Analytics: pytest passes on its separate branch. Execute BQ4 and BQ14 SQL
   on a temporary PostgreSQL test database with representative mixed Kotlin/
   Flutter events; then validate on authorized hosted data without modifying
   production rows. Merely passing string tests is insufficient.
3. Real device: fresh sign-in, network-first and offline catalogue, saved-plan
   persistence after restart and account switch, one-tap accept success/failure,
   all four views, correct event names/attempt IDs, recovery after reconnection.
4. Verify an actual BQ4 output with both paths, BQ7 data provenance,
   BQ8 both experimental arms (if real users exist), and BQ14 mature cohorts.
   Do not fabricate missing observations.
5. Compare performance with Android Studio CPU/Frame/Memory Profiler and
   supporting logs; attach real before/after evidence to the Wiki.
6. Integrate the approved code through independently reviewed GitHub PRs
   created by the team; test the merged `main` before Firebase distribution.

## Micro-optimization candidate and fair before/after

`ScreensDiscover.kt` memoizes recomposition-derived recommendation, filtered
catalogue and quick-plan lists using Compose `remember`. The inputs include
catalogue load/source, filters, preferences, progress and context to avoid stale
lists. This may reduce allocations under unrelated recompositions, but any
benefit MUST be verified with a profiler.

Compare these exact commits with the SAME device, account, catalogue and
scripted interactions:

- Before memoization: `e247eb91b61b22959e4eb03f084e5955b03e8a35`
- After memoization: `57c74ffe6000abaf8d07956a35d1da4116c1dc53`

Repeat warm and cold runs, report samples, frame percentiles/janky frames and
CPU/allocations, and explain variability. If there is no repeatable improvement,
report that finding instead of claiming higher performance. The app's other
Sprint 4 changes are present in both snapshots, making this comparison more
meaningful than comparing against Sprint 3's app.

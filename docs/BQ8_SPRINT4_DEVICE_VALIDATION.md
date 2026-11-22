# BQ8 — Sprint 4 Kotlin: technical validation protocol

> **Team working notes, not a final Wiki submission.** The students must execute the
> steps, inspect the evidence and write/approve the graded submission themselves.
> BQ8 is Sebastián's proposed NEW Sprint 4 BQ; his historical BQs are BQ5 (S2)
> and BQ9 (S3). BQ12 is not his completed S3 work.

## Scope and intended evidence

**Question (Type 3):** How diverse are the recommendations shown to users over time?

- Shared backend: `public.recommend_quests` (migration 008, server-assigned
  `control` / `diverse`, plus `rank_position`).
- Kotlin: reads server variant/rank, preserves the RPC rank in Smart Picks,
  logs an impression **when a card enters the visible Explore viewport**,
  groups impressions with a fresh `batch_id`, and avoids duplicate impressions
  from cache hits or repeated recompositions.
- Analytics: `BQ8_RECOMMENDATION_DIVERSITY_SQL`, job `bq8` in
  `Sidequests-Analytics`. Data originates in `public.analytics_events`;
  results are stored in `analytics.bq_results`.
- Sprint 4 new view: **Something Different** (Explore > Try variety) offers a
  locally generated category-diverse shortlist, separate from the server A/B
  experiment. Opening details is NOT a `recommendation_accepted` event.
- Existing support: saved quest catalogue for offline browsing, in-memory
  recommendation cache with TTL/stale-if-error and local WorkManager outbox.
- Not proven: hosted batch output, comparative benefit of the diverse arm,
  physical-device behavior, upload of queued events or profiler gains.

For the combined BQ8 experiment + Something Different test, use the
`integration/s4-bq8-check` **verification branch**, which contains both
independently reviewable Kotlin changes. After the team merges both PRs,
repeat a smoke test on `main`. The integration branch is NOT a PR.

## Tester setup — Android device (not emulator)

1. Clone an isolated working directory (do not overwrite existing local work):
   `git clone https://github.com/Moviles-SEC-2-G25/Sidequests-Kotlin.git`.
2. Enter the directory, run:
   `git fetch origin`;
   `git switch integration/s4-bq8-check`;
   `git pull --ff-only origin integration/s4-bq8-check`.
   Record the tested commit: `git rev-parse --short HEAD`.
3. Install Android Studio, Android SDK 36 and USB/device drivers if needed.
   Connect a real Android device (Android 8.0 / API 26 or later) with USB
   debugging enabled; accept the device trust dialog. Enable developer options
   on the phone as appropriate. Confirm with `adb devices` and look for
   `device`, not `unauthorized`.
4. Configure **only** the user's local Gradle properties (Windows:
   `C:\Users\<user>\.gradle\gradle.properties`; macOS/Linux:
   `~/.gradle/gradle.properties`) with the authorized team's
   `SUPABASE_URL` and **publishable/anon** `SUPABASE_PUBLISHABLE_KEY`.
   See `gradle.properties.example`. Never use or distribute the Supabase
   service-role/secret key. Android Studio's `local.properties` is for SDK
   path, not a committed secret file.
5. Run `gradlew :app:testDebugUnitTest :app:assembleDebug`
   (Windows: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`).
   Select the connected device and Run **app** from Android Studio. Do not
   rely on the CI APK for Supabase checks: its build has no supplied backend
   settings by default and may run only a local/sample catalogue.
6. Use an approved **test account**, not a personal or TA account. Verify
   authentication succeeds and Explore labels the catalogue `Supabase`,
   not merely `Sample catalogue`.

## Tester A — online BQ8, screenshot and telemetry

1. Record OS version/model, local test date/time, commit and account alias
   (never passwords or private IDs). Take a screenshot of Explore after load.
2. Check Smart Picks are marked `Supabase BQ5`. In a debug build, the header
   also labels `BQ8 test · server arm: control/diverse` when a *fresh* batch
   was received. If it says `unassigned`, report a blocked experiment:
   do not invent an arm.
3. Scroll so each Smart Picks card is actually visible. Record the display
   order and screenshot (no credentials, personal location or photos).
4. Open **Try variety / Something Different**. Confirm it shows several
   *distinct* categories when the catalogue permits it; note when it cannot.
   Tap one candidate: it should open quest details rather than accept it.
5. Return to Explore, tap `Not for me` on a Smart Picks quest, and observe
   the new list if available. Do not claim each refresh used the network:
   TTL cache hits show a source label and do **not** create BQ8 impressions.
6. If authorized to create a disposable quest attempt, open a Smart Picks
   quest and accept it online. Check successful backend acceptance before
   relying on `recommendation_accepted` telemetry. Do not create fake
   long-term completion data to prove experimental effects.
7. Have the member with Supabase read access run the **read-only** diagnostics
   in `docs/BQ8_SPRINT4_DIAGNOSTICS.sql`. Verify:
   - `variant` is `control` or `diverse` (or explicitly `unassigned` for
     legacy events);
   - `rank` is positive, `batch_id` is present and shared by one list;
   - no repeated `quest_id` within a batch, and the order shown matches rank;
   - the app's `rpc_viewport` events exist and have the intended timestamp.
   The actual BQ8 pipeline output must be tested separately. Redact identifiers
   in any screenshots shared with students/in the Wiki.

## Tester B — eventual connectivity and reliability

1. While online, authenticate and load Explore and Try variety once so
   catalogue data is saved. Take a screenshot showing source labels.
2. Switch on Airplane mode; confirm mobile data and Wi-Fi are off. Navigate
   back to Explore / Try variety. It should display saved data or a clear
   fallback label; **do not** claim server recommendations were updated.
3. Without internet, open a candidate in Try variety. Record whether detail
   works and whether attempting to accept a quest shows a clear message.
   Kotlin deliberately does not create a new backend attempt offline.
4. If a supported queued event was created, reconnect. Verify the app
   retries outbox sync and confirm it in Supabase with read access.
   Without database evidence, report this as *not yet validated*.
5. Restart the app, sign in again, check cached behavior and account isolation.
   Any repeated impressions without a new RPC batch or stale labels are defects.
6. Send the test report: commit, device/OS, each step pass/fail, screenshots,
   failures and sanitized logs. Do not send raw access tokens, user IDs, email
   addresses, photos, exact GPS positions or credentials.

## Performance profiling (Sprint 4 team-wide, **40 Wiki points**)

This is an app-wide task and is **not proof that BQ8 itself becomes more
diverse**. To demonstrate the existing Compose micro-optimization in Explore,
use the same physical phone, app data and scripted scroll/filter sequence:

- BEFORE `e247eb91b61b22959e4eb03f084e5955b03e8a35`
- AFTER  `57c74ffe6000abaf8d07956a35d1da4116c1dc53`

These two snapshots differ in the candidate `remember` memoization.
Build **both** with identical Supabase config, build type and UI workload.
Use Android Studio > Profiler > record System Trace/CPU and Java/Kotlin
allocations, repeat each condition several times. Note frame timing/jank
and allocations/CPU as appropriate. Export real recordings and screenshot
charts showing the measurements. No metric value or performance claim may be
written until captured. If no repeatable improvement is found, disclose it
and profile another bottleneck.

## Suggested personal Wiki structure (fill only after real validation)

1. S4 · Sebastián — BQ8 (Type 3): question and why diversity matters.
2. S2 BQ5 / S3 BQ9 / S4 BQ8: clear historical boundary.
3. Design and architecture: PostgreSQL RPC -> Kotlin -> analytics_events ->
   Analytics BQ8 pipeline -> bq_results -> dashboard.
4. Experiment: server control/diverse (actual logic); intended metrics and
   comparisons; no causal claim until data support it.
5. Implementation: exact files, links to approved PRs, source snippets.
6. New S4 view: Something Different, screenshots and offline UI.
7. Validation: unit tests, latest commit/CI, physical device, database
   diagnostics, pipeline outputs, missing evidence clearly marked.
8. Strategies: concurrency, local storage, caching and eventual connectivity
   that the functionality really uses; explain failure handling and boundaries.
9. Results: observation window, sample sizes, arm-level metrics, known biases,
   business implication. Missing/one-arm data must be reported honestly.
10. Micro-optimization: before/after profiling evidence and exact diffs; if
    produced elsewhere in the team Wiki, link the shared section.
11. Limitations, remaining tasks and citations (Sprint 1 BQ list, repository,
    course rubric, screenshots and logs).

**Exit gate:** New code may be merged only after the team reviews it, current
CI passes and the mobile + data checks above are completed or limitations are
explicitly accepted. Only the user/team creates issues, milestones and PRs.

# Sprint 4 Kotlin–Flutter parity audit

Kotlin baseline `e109ce17a5b4f40ad60bbf6e257407d6ece98b73`. Flutter reference `e9a8e92f29323acecf743b3935aeca9c5340e101`. The comparison records behavior found in source at those commits. Device and shared-backend equivalence remain unverified unless explicitly reported.

| Screen or flow | Flutter reference behavior | Kotlin baseline and gap |
| --- | --- | --- |
| Navigation | `HomeShell` keeps five tabs and pushes detail routes over the current tab. The Quest tab also has a no-active-quest state. | Partial. `SidequestsApp` and `BottomBar` model Explore, Quest and Profile as destinations and disable Quest without an open attempt. Nearby and Social destinations, tab state retention and detail return routing differ. |
| Login, signup and onboarding | `AuthGate` routes to password, OTP, biometric lock and onboarding. `AuthViewModel` guards OTP requests offline and uses a local biometric preference for existing sessions. | Partial. Password login, signup, session restore and onboarding exist in `AuthViewModel`, `AuthScreen` and `OnboardingScreen`. Email OTP and biometric entry are missing. Layout, field sequence and account lifecycle need comparison in execution. |
| Explore and Smart Picks | `ExploreView` shows time and social controls, recommendation cards, instant start and skip. `QuestViewModel` uses a session social override and logs the recommendation journey. | Partial. `ExplorerScreen` uses BQ5 recommendations, local fallback, persistent preferences, BQ8 impressions and BQ14 filtering. Instant start, BQ4 and session-only social controls are missing. Preserve backend ordering. |
| Nearby | `NearbyView` selects GPS-mode quests and sorts real measured distances before unavailable distances. Location permission and missing fixes are explicit. | Missing destination. Kotlin has location permission and context capture, but `Quest` only exposes a display distance and `catalogueDistanceMeters` parses a catalogue estimate. Preserve remote coordinates through cache before showing measured distances. |
| Social | `SocialView` shows the unavailable friends-photo feed state. `GroupChallengesView` implements actual group workflows separately. | Missing Social tab. Existing `GroupChallengesScreen` is the functional route for groups. Surface it without claiming friend or photo-feed data exists. |
| Quest detail | `QuestDetailView` leads to acceptance or resumption and uses address/compass widgets for eligible destinations. | Partial. `QuestDetailScreen` handles acceptance, resumption, steps and sponsorship. Live bearing and Nearby return path are missing. |
| Active quest and verification | `MissionTabView` uses verification strategies, foreground location sampling, arrival events, photo capture and attempt-scoped walking sessions. | Partial. `ActiveQuestScreen` and `QuestStepCompletionPolicy` guard photo evidence and upload requirements. Progress and evidence already have durable queues. No pedometer, coordinate-based check-in or compass. |
| Exit and alternatives | `AbandonMissionView`, `QuestAlternativesView` and the recommender use abandonment reasons and contextual alternatives. | Implemented in `ExitFlowScreen`, `QuestAlternativesScreen` and `QuestAlternativeRecommender`. Rendering and scoring equivalence remain unverified. |
| Completion and rating | `QuestCompletedView` supports rating and up to three locally ranked “You might like” suggestions. Rating changes steer similarity and completed/open quests are excluded. | Partial. `RatingScreen` and `submitRating` persist ratings, then return to Explore. Similar suggestions are missing. |
| Profile and statistics | `ProfileView` links to preferences, history, offline pack, biometric settings and `StatsView`. Stats aggregate attempts, completions, steps and personal category rates. | Partial. `ProfileScreen` exposes settings/history/offline pack and summary tiles. Dedicated statistics and Explore completion likelihood are missing. `HistorySnapshot` can carry attempt step totals, but `SupabaseHistoryRemote` supplies none and Kotlin has no step log. |
| Mission History | `MissionHistoryView` shows dated attempts, search, filters, month totals, saved data notices and evidence routes. | Implemented through `MissionHistoryRoute`, `MissionHistoryController` and an account-keyed `MissionHistoryRepository`. Walking totals depend on a future step log. |
| Attempt Evidence | `AttemptEvidenceView` renders availability, save-all and full-screen evidence. | Implemented through `AttemptEvidenceScreen`, thumbnail caching and `AttemptEvidenceRepository`. Live proof/download equivalence remains unverified. |
| Group Challenges | `GroupChallengesView` uses create, join and other stateful actions with pending/error presentation. | Implemented through `GroupChallengesScreen` and the existing BQ2 repository. Preserve this contract when adding a Social destination. |
| Offline Pack Manager | `OfflinePackView` presents cached, pending and failed downloads with retry/removal. | Implemented through `OfflinePackScreen` and `OfflinePackManager`. Preserve manifest optimization and download retry behavior. |
| Social Comfort | Flutter's Explore session override selects the current level and annotates recommendation context. | Kotlin has separate persisted BQ14 comfort settings, experiment filtering and reporting. A transient recommendation preference is missing. Keep the two controls independent. |
| Settings and themes | `ProfileView`, `ThemeViewModel` and `app_theme.dart` define theme, account settings and shared design tokens. | Partial. Core palette matches. Kotlin also supports ambient-light theme, password change and device settings. Compare typography, surfaces and density without removing device functionality. |
| Weather, night and surprise | Flutter uses context for rainy/nighttime options and a foreground Explore-only shake listener with a cooldown. | Partial. `ContextAwareRecommendationEngine` uses weather and time on local lists and Kotlin has battery-aware context. Explore shake is missing. Weather/night UI and filter behavior differ. |
| Offline, loading, error and recovery | `HomeShell` refreshes on stable reconnection, renders offline notices and retries queues. New attempts generally require network. | Implemented with deliberate differences. Global banner, catalogue/recommendation fallback, retry callbacks, and durable progress, photo and analytics queues exist. Kotlin supports queued offline starts for eligible quests. Screen-specific empty and loading parity needs runtime verification. |
| Business Questions | Flutter attaches BQ4 fields to one acceptance/start event. Other paths carry recommendation, onboarding, abandonment, context and commercial evidence. | Partial. Kotlin has BQ2, BQ5, BQ6, BQ8, BQ9, BQ11, BQ13 and BQ14 contributions. BQ4 path/timing metadata is absent. Preserve existing effect boundaries, including confirmation-gated acceptance and viewport-gated impressions. |

Use native Compose routes and reuse repositories. Build statistics from account-scoped history and distinguish unavailable steps from a recorded zero. Add BQ4 metadata to the existing confirmed recommendation event. Keep new context controls independent of BQ8 order and BQ14 preference persistence.

## Implementation on the local parity branch

| Area | Implemented in Kotlin | Remaining verification or difference |
| --- | --- | --- |
| Instant Plan and BQ4 | Smart Picks can start directly or continue their original attempt. The confirmed `recommendation_accepted` event carries `start_path`, `seconds_to_start`, `interactions_to_start` and the observed batch when available. The existing first-step event and durable outbox remain in use. | BQ4 measures Smart Picks attributed through the shared recommendation source. Local fallback can still start a quest. A live analytics row and the downstream BQ4 aggregate require a test account. |
| Statistics and completion likelihood | My Statistics aggregates attempts, completions, category rates and attempt-specific walking data from the account-scoped history source. Explore uses the same thresholds as Flutter. The profile no longer shows a hardcoded streak. | Cross-device step totals are unavailable because walking data is stored locally. The history and statistics views also need an authenticated runtime comparison. |
| Completion and rating | Rating uses a scrollable star form. Local suggestions adjust to the selected rating, exclude completed/open attempts and respect the current time limit. Opening a suggestion retains the rating draft when returning. | Exercise real completion, rating sync and suggestion navigation on a signed-in build. |
| Nearby and Social | Five bottom destinations, GPS-only catalogue sorting, explicit unavailable distances, a Quest empty state and a Social unavailable-feed view linked to existing Group Challenges. | Compare on equivalent devices and real catalogue coordinates. Friend feeds depend on shared data beyond this Kotlin implementation. |
| Session social preference | An account-session override updates BQ5 requests and local fallback, with source metadata on recommendation events. Obsolete in-flight requests cannot overwrite a newer filter. BQ14 settings remain a distinct persisted control. | Verify the effective social level in real RPC results and analytics. |
| Walking, GPS and compass | Native step counter with per-attempt local totals, foreground GPS check-in respecting account, pause, accuracy and photo confirmation, and a true-north-corrected compass. Explicitly saving and exiting pauses sensors. | The walking counter banks foreground segments and resumes from saved totals. Flutter can count steps while a persisted session is suspended. Kotlin adjusts GPS sampling for low battery. Flutter also changes precision and interval as the user approaches. Verify permissions, counters, fix accuracy and compass direction on hardware. |
| Authentication | Supabase email OTP uses a six-digit code for an existing account, a resend cooldown and offline guards. AndroidX Biometric protects saved sessions with a per-account opt-in and strong device biometrics. | Verify that the project sends a numeric token using its email template, then complete OTP verification and biometric enrollment/unlock on a device. The separate Flutter profile preferences and additional signup/onboarding layout details remain different. |
| Shake and context | Foreground Explore shake detection, a button fallback, current filters, saved-catalogue support, night/rain prioritization of Anywhere quests and contextual weather cards. | Live Open-Meteo and physical gesture behavior need verification. Weather forecasts use the existing in-memory cache. |
| Visuals and English strings | Bundled Outfit and DM Sans, light/dark surface tokens, outlined tab icons, revised detail and auth layouts, flexible action/badge rows, and English Social Comfort labels. | Side-by-side layout, full dark-theme and accessibility validation across authenticated screens remain open. Kotlin retains distinct onboarding, Profile preferences, Mission History, Attempt Evidence, Group Challenges, Offline Pack Manager and Social Comfort layouts. |

The BQ4 implementation was checked against the [pinned Analytics event schema](https://github.com/Moviles-SEC-2-G25/Sidequests-Analytics/blob/8b6cca139711153d0d87f4ecf240a97bfe61f3e4/docs/EVENT_SCHEMA.md). The nullable location and verification fields were checked against the [pinned foundation migration](https://github.com/Moviles-SEC-2-G25/Sidequests-Backend/blob/b1457d661ac23291d0035dd1b90dbf6eab202c79/supabase/migrations/001_foundation_schema_rls.sql) and [optional-coordinate migration](https://github.com/Moviles-SEC-2-G25/Sidequests-Backend/blob/b1457d661ac23291d0035dd1b90dbf6eab202c79/supabase/migrations/003_make_quest_coordinates_optional.sql).

Font and license blobs were verified against Google Fonts commit `5026800714d07231937d92eb4cceabf06e05d74e`. The OFL texts are bundled with the app. [Outfit](https://github.com/google/fonts/tree/5026800714d07231937d92eb4cceabf06e05d74e/ofl/outfit) · [DM Sans](https://github.com/google/fonts/tree/5026800714d07231937d92eb4cceabf06e05d74e/ofl/dmsans)

## Build and regression evidence

| Check | Baseline | Final |
| --- | --- | --- |
| `:app:testDebugUnitTest` | 174 passed | 188 passed across 50 suites, zero failures or skips |
| `:app:assembleDebug` | Passed | Passed. APK SHA-256 `f191f1065a5f18cdbd519658db8fbb42a9fcef3642569a7c58cb513d2219a161` |
| `:app:lintDebug` | Passed | Passed with zero errors, 18 warnings and two hints |
| `git diff --check` | Clean | Passed |

The original environment pointed `JAVA_HOME` to Android Studio's JBR 25. The required baseline and final checks both passed with an explicit process-local Temurin 17.0.16 setting, Gradle 9.3.1 and SDK 36. The baseline compiler reported an unused expression in `QuestProgressRepository.kt`. Its APK installed and reached the offline auth UI. A System UI ANR and the inspection tool's timeout limited that baseline smoke test.

Targeted tests passed after each functional group. An intermediate Social Comfort regression test exposed lost refresh requests after rapid filter changes. The final implementation cancels stale requests and keeps duplicate in-flight requests for identical inputs deduplicated. During visual work a formatting script temporarily emptied the Explore source. It was reconstructed from the verified Kotlin baseline and the intended changes were reapplied before the final checks.

OTP's six-digit delivery depends on the Supabase email template. The unit suite exercised the ViewModel with a fake repository, including account binding, resend and offline guards, with zero requests to a shared project. [Supabase sign-in with OTP](https://supabase.com/docs/reference/kotlin/auth-signinwithotp) · [Supabase verify OTP](https://supabase.com/docs/reference/kotlin/auth-verifyotp)

## Android smoke test

The final APK installed and ran on a read-only Pixel 7 AVD instance with API 37. Android ran in airplane mode with Wi-Fi and mobile data disabled. The copy used fresh app data for the signed-out test. The test exercised password login's offline guard, navigation to and from email code, its disabled send action, and offline signup. Signup stayed open after switching to a 360 dp viewport and 1.3 font scaling. A local fixture set the device's app theme, then a cold launch showed the dark surface and the corrected offline-banner contrast. Android logged zero Sidequests crashes or ANRs during the final smoke test.

An API 36.1 QA image repeatedly crashed in SurfaceFlinger before installation. The Pixel 7 instance initially displayed a System UI timeout and recovered after choosing Wait. Local screenshots are under `build/parity-reference` as `final-login.png`, `final-otp.png`, `final-signup-small.png` and `final-dark-login.png`.

The reference repository had no APK attached to the fixed Flutter commit, and Flutter tooling and shared test accounts were unavailable in this environment. The runtime comparison of signed-in screens, shared backend behavior, telemetry, OTP delivery, biometrics and real sensor inputs remains open. Recommend a draft pull request for review while those checks and the remaining differences in the implementation table are addressed.

## Changed files

The parity branch is local, with 28 modified tracked files and 34 new files. HEAD is the verified Kotlin checkpoint.

```text
 M app/build.gradle.kts
 M app/src/main/AndroidManifest.xml
 M app/src/main/java/com/sidequests/app/MainActivity.kt
 M app/src/main/java/com/sidequests/app/context/ContextManager.kt
 M app/src/main/java/com/sidequests/app/context/ContextModels.kt
 M app/src/main/java/com/sidequests/app/context/LocationProvider.kt
 M app/src/main/java/com/sidequests/app/context/WeatherService.kt
 M app/src/main/java/com/sidequests/app/data/SidequestsRepository.kt
 M app/src/main/java/com/sidequests/app/data/auth/AuthRepository.kt
 M app/src/main/java/com/sidequests/app/data/history/MissionHistoryRepository.kt
 M app/src/main/java/com/sidequests/app/data/local/LexLocalStore.kt
 M app/src/main/java/com/sidequests/app/data/remote/QuestRemoteModels.kt
 M app/src/main/java/com/sidequests/app/model/Models.kt
 M app/src/main/java/com/sidequests/app/ui/AppViewModel.kt
 M app/src/main/java/com/sidequests/app/ui/AppViewModelFactory.kt
 M app/src/main/java/com/sidequests/app/ui/Components.kt
 M app/src/main/java/com/sidequests/app/ui/GroupChallengesScreen.kt
 M app/src/main/java/com/sidequests/app/ui/MissionHistoryController.kt
 M app/src/main/java/com/sidequests/app/ui/MissionHistoryScreen.kt
 M app/src/main/java/com/sidequests/app/ui/ScreensDiscover.kt
 M app/src/main/java/com/sidequests/app/ui/ScreensQuest.kt
 M app/src/main/java/com/sidequests/app/ui/SidequestsApp.kt
 M app/src/main/java/com/sidequests/app/ui/SocialComfortScreen.kt
 M app/src/main/java/com/sidequests/app/ui/auth/AuthScreen.kt
 M app/src/main/java/com/sidequests/app/ui/auth/AuthViewModel.kt
 M app/src/main/java/com/sidequests/app/ui/theme/SidequestsTheme.kt
 M app/src/test/java/com/sidequests/app/context/OpenMeteoWeatherProviderTest.kt
 M app/src/test/java/com/sidequests/app/ui/OfflineQuestQueueFlowTest.kt
?? app/src/main/assets/licenses/dm_sans.txt
?? app/src/main/assets/licenses/outfit.txt
?? app/src/main/java/com/sidequests/app/context/QuestCheckInProvider.kt
?? app/src/main/java/com/sidequests/app/data/auth/BiometricLockStore.kt
?? app/src/main/java/com/sidequests/app/data/history/WalkingTotalsStore.kt
?? app/src/main/java/com/sidequests/app/domain/ExploreCatalogue.kt
?? app/src/main/java/com/sidequests/app/domain/NearbyQuests.kt
?? app/src/main/java/com/sidequests/app/domain/QuestStatistics.kt
?? app/src/main/java/com/sidequests/app/domain/SimilarQuestRecommender.kt
?? app/src/main/java/com/sidequests/app/domain/WalkingSegment.kt
?? app/src/main/java/com/sidequests/app/sensor/ShakeDetector.kt
?? app/src/main/java/com/sidequests/app/ui/DiscoveryTabs.kt
?? app/src/main/java/com/sidequests/app/ui/QuestCheckIn.kt
?? app/src/main/java/com/sidequests/app/ui/QuestCompass.kt
?? app/src/main/java/com/sidequests/app/ui/QuestStatisticsScreen.kt
?? app/src/main/java/com/sidequests/app/ui/QuestWalking.kt
?? app/src/main/java/com/sidequests/app/ui/QuestWeatherCard.kt
?? app/src/main/java/com/sidequests/app/ui/SurpriseQuestGesture.kt
?? app/src/main/java/com/sidequests/app/ui/auth/BiometricUnlock.kt
?? app/src/main/java/com/sidequests/app/ui/auth/EmailCodeScreen.kt
?? app/src/main/res/drawable/ic_explore.xml
?? app/src/main/res/drawable/ic_nearby.xml
?? app/src/main/res/drawable/ic_profile.xml
?? app/src/main/res/drawable/ic_quest.xml
?? app/src/main/res/drawable/ic_social.xml
?? app/src/main/res/font/dm_sans.ttf
?? app/src/main/res/font/outfit.ttf
?? app/src/test/java/com/sidequests/app/domain/ExploreCatalogueTest.kt
?? app/src/test/java/com/sidequests/app/domain/NearbyQuestsTest.kt
?? app/src/test/java/com/sidequests/app/domain/QuestStatisticsTest.kt
?? app/src/test/java/com/sidequests/app/domain/SimilarQuestRecommenderTest.kt
?? app/src/test/java/com/sidequests/app/domain/WalkingSegmentTest.kt
?? app/src/test/java/com/sidequests/app/ui/auth/AuthParityFlowTest.kt
?? docs/SPRINT4_PARITY_AUDIT.md
```

## Stabilization audit — 2026-11-28

The official `main` refs for Kotlin and Flutter still matched the checkpoints at the top of this report when checked with `git ls-remote`. Kotlin remained on `feature/s4-kotlin-flutter-parity` at `e109ce17`. The earlier sections describe the parity implementation and its previous evidence.

The baseline comparison of `ScreensDiscover.kt` confirmed that onboarding and its difficulty options were unchanged and that the original Explore actions remained wired. Smart Picks' visible-item boundary now calls `smartPickVisible`, which forwards to the existing BQ8 observation. The general catalogue adds the intended time and social filters. Group Challenges, abandonment alternatives, Try variety, durable recommendation analytics, commercial impressions, adaptive difficulty, and Social Comfort retained their entry points and effect paths. History, evidence, offline packs, cached reads, deferred writes and the foreground ambient-light observer also remained wired. The Kotlin-authored UI text reviewed is in English. Dynamic quest content and responses from the shared project require a signed-in UI pass.

Two corrections were made. Quest progress checks the attempt and step displayed when the action was rendered and rejects calls for paused, abandoned or completed attempts. Both the manual button and GPS check-in use the same guard. Direct progress sync ignores stale callbacks after an account switch and direct lifecycle analytics use the account captured when the event occurred. The unit regression exercises repeated taps, a stale attempt, the terminal step and an account change. It records two progress operations for two steps and a single start and completion owned by the original account. Durable events keep their existing outbox and confirmation gate.

The pinned Analytics schema allows BQ4 metadata on `recommendation_accepted` or `quest_started` and expects exactly one event with those fields per measured start. The Kotlin tests cover both `instant_plan` and `standard` on the confirmed recommendation acceptance, including timing and interaction counts. Direct lifecycle and first-step events retain their independent meanings. BQ8 visibility, BQ9 delivery, BQ11 commercial evidence, BQ6 abandonment and BQ14 comfort have separate tests. Live inserts and downstream aggregates were outside the available test evidence. [Analytics event schema](https://github.com/Moviles-SEC-2-G25/Sidequests-Analytics/blob/8b6cca139711153d0d87f4ecf240a97bfe61f3e4/docs/EVENT_SCHEMA.md)

### Final build checks

| Check | Stabilization result |
| --- | --- |
| `:app:testDebugUnitTest` | 189 passed. Zero failures, errors or skips. |
| `:app:assembleDebug` | Passed. APK SHA-256 `80067308a2c9c0164c5c7618e901a3654e2e9d43ed5722e809d89e672764a437`. |
| `:app:lintDebug` | Passed with zero errors, 18 warnings and two hints. |
| `git diff --check` | Passed. |

Gradle used Temurin 17.0.16 with at most two workers. The pre-stabilization APK and the revised APK both showed the English offline login notice, disabled password login and code requests, and navigable email-code and signup screens on the read-only Pixel 7 API 37 emulator. The revised signup remained readable and reachable at 360 dp with font scale 1.3. The signed-out runtime used fresh app data, and no account, password or OTP was submitted.

Android recorded an input-focus ANR on the pre-stabilization APK and a startup ANR on the revised APK during dark-mode cold-start testing. The revised APK also had a process start timeout that returned to the Android launcher. A later run reached and rendered the dark login at the same size and font scale. The emulator also reported a System UI ANR and CPU pressure. The app ANR trace was inaccessible to the shell, so the cause remains open. The corrected dark screenshot is `build/parity-reference/stabilization-dark-observed.png`. The retained `final-dark-login.png` from the timed-out run shows the launcher and is evidence of the failed capture.

After the Flutter build exited, the revised Kotlin APK reached signed-out login on three consecutive cold starts in the same emulator, with no new app ANR or crash events. Android reported activity launch times of 11.872 s, 5.781 s and 5.605 s. This run establishes reproducibility of the successful path on this emulator without a concurrent build. Cold-start latency and the earlier app ANRs remain open for device validation.

The signed-out app displayed “You're back online” and re-enabled password login on a second reconnection attempt after airplane mode was disabled and Wi-Fi and mobile data were enabled on the emulator. No sign-in action was taken. The first reconnection attempt, with Wi-Fi alone, stayed offline during observation. The test restored airplane mode and disabled both transports.

### Flutter reference and visual verification

The reference was obtained from `e9a8e92`. The build attempts ran in an ignored build directory with Flutter 3.50.0 and Dart 3.14.0. The official worktree and remote were untouched. Build setup added an offline placeholder endpoint and resolved four dependency entries in the scratch copy. The first attempt rejected AGP 9.1.0 because the available Flutter toolchain required at least 9.1.1. A second attempt used Flutter's build-time validation flag without editing the reference Gradle scripts. The SDK installed Android Platform 37.0, but AGP could not resolve the pinned build target `android-37`. The SDK manager's catalogue listed `android-37.0` and later 37.x revisions. The exact `android-37` identifier was absent. Neither build produced an APK.

The Kotlin screenshots verified signed-out routes, typography, disabled actions, light and dark surfaces, and the 360 dp signup form with enlarged text. Source inspection found that Kotlin places the connection notice above the auth form, while Flutter places it inside the form. Kotlin's signup collects a name, while Flutter's signup collects email and password. Side-by-side comparison at matching dimensions and authenticated loading, error, navigation and offline states remain unverified.

### Verification matrix

| Functionality | Implemented | Unit-tested | Runtime-tested | Visually compared | Blocked or unverified |
| --- | --- | --- | --- | --- | --- |
| Registration, password login and onboarding | Yes | Auth guards and state | Signed-out offline entry and disabled actions | No | No assigned test account or approved signup inbox. Live signup, login and onboarding were not exercised. |
| Email OTP and biometric unlock | Yes | Auth state with fakes | Offline OTP navigation and disabled request | No | Email template, token delivery and device biometrics. |
| Explore and BQ5 Smart Picks | Yes | Catalogue, cache and recommendation flows | No | No | Signed-in rendering and remote catalogue and recommendations. |
| Quest acceptance, progress and completion | Yes | Online/offline queue and stale actions | No | No | Signed-in run, async backend confirmation and device verification. |
| Abandonment and alternatives (BQ6) | Yes | Abandonment evidence and ranking | No | No | Remote status, event delivery and the live alternatives screen. |
| Group Challenges (BQ2) | Yes | Loader and presentation | No | No | Create/join workflows need test identities and an approved group. |
| Mission History | Yes | Repository, controller and aggregation | No | No | Requires an account with attempts. |
| Attempt Evidence | Yes | Repository, local photos and sync | No | No | Requires an attempt with evidence to verify display/download. |
| Offline Pack Manager | Yes | Supporting catalogue and photo-cache paths | No | No | Manager UI and pack download, retry and deletion. |
| Offline, reconnection, caching and deferred writes | Yes | Local storage, progress, evidence and analytics queues | Signed-out offline guard and recovery | No | Signed-in cache hydration, queue drain and reconciliation. |
| Instant Plan and BQ4 | Yes | Both acceptance paths and attribution | No | No | Live analytics row and the resulting aggregate. |
| My Statistics and Completion Likelihood | Yes | Aggregation and category rates | No | No | Signed-in history and local step totals. |
| You Might Like and rating | Yes | Similarity and exclusions | No | No | Completion-to-rating interaction and durable rating. |
| Nearby and five destinations | Yes | Nearby filtering and ordering | No | No | Tab/back behavior and GPS permission/fix handling in the app. |
| Account change and data isolation | Yes | Scoped local flows and stale progress/analytics ownership | No | No | Live isolation requires two approved test identities. |
| Impressions and Try variety (BQ8) | Yes | Viewport, deduplication and presentation | No | No | Live impression and variety attribution. |
| Durable analytics (BQ9) | Yes | Outbox, retry and offline flow | No | No | Live event delivery and deduplication after reconnection. |
| Commercial measurement (BQ11) | Yes | Commercial observation | No | No | Actual exposure of a sponsored block and its live event. |
| Adaptive difficulty (BQ13) | Yes | Advisor and completion-driven updates | No | No | Signed-in preference and history behavior. |
| Social Comfort (BQ14) | Yes | Advisor, repository and refresh flow | No | No | Persisted choice and Explore session override against remote data. |
| Ambient-light monitor and theme | Yes | Adjacent settings behavior | Theme fixture only | No | Real sensor readings and ambient transitions. |
| Walking, GPS, compass, weather and shake | Yes | Step policy, walking segments and forecast mapping | No | No | Permissions, real sensors and live weather. |

### Lint triage

| Count | Severity | Finding |
| --- | --- | --- |
| 1 | Low, visible packaging | `MissingApplicationIcon` in the manifest. |
| 7 | Low, maintainability | `UseKtx` suggestions for shared preferences (5), bitmap scaling (1) and SQLite (1). |
| 10 | Informational dependency notices | `GradleDependency` checks for Core KTX, Play Services Location, three Lifecycle components and DataStore. `NewerVersionAvailable` checks for the Supabase BOM, Ktor and two Coroutines dependencies. |

The two existing hints are Compose primitive state boxing in Attempt Evidence. No warnings were suppressed, no dependencies were upgraded and no tests were weakened.

The additional whitespace check of 32 untracked text files found the same trailing space on line 21 of both bundled font licenses. The license copies were preserved byte for byte from the upstream blobs recorded above.

**CONDITIONAL GO for manual review.** Before integration, verify cold-start stability on a representative device, complete the authenticated flows with approved test identities, and run a side-by-side comparison with a Flutter APK from the pinned reference. Full parity remains unverified. The branch remains local with no staged changes.

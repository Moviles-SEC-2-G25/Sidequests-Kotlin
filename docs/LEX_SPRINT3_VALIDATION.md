# Lex — Sprint 3 development and validation notes

Starting state verified on 2026-10-29: Kotlin HEAD and remote main both
`f382d1e346a7a6d80b8863a246271aee18ac52bd` after fetch. Work branch:
`feature/lex-sprint3-onboarding-photo-queue`. The pre-existing untracked
`gradle/gradle-daemon-jvm.properties` was preserved.

## Scope

- BQ3 v2: four real setup steps, completion, explicit Skip, restored journey,
  first visible recommendation and its explicitly attributed acceptance.
- Photos: private persistent outbox, account ownership, local evidence and
  upload state, recoverable interrupted work, network-constrained retry.
- Analytics counterpart is in Sidequests-Analytics, branch
  `feature/lex-sprint3-bq3`, based on `48fc5bba8b91f284af1d241022c4dc5868ccc01f`.
  Its `docs/BQ3_V2.md` defines the metrics and `docs/BQ3_V2_VALIDATION.sql` checks
  actual events, linked attempts and the stored answer.

## Code map

| Concern | Implementation |
| --- | --- |
| Setup transitions, preference snapshots and event contract | `data/analytics/OnboardingRecorder.kt` |
| Atomic private file / durable events and photos | `data/local/LexLocalStore.kt` |
| Network-constrained persistent background work | `data/local/LexSyncWorker.kt` |
| Photo retry state machine and cancellation recovery | `data/photo/PhotoProofSyncEngine.kt` |
| Stable Storage path, registration check and retry acknowledgement handling | `data/photo/PhotoProofRepository.kt` |
| Account restoration, UI coordination and serialized local updates | `ui/AppViewModel.kt` |
| Real Smart Picks exposure | `ui/ScreensDiscover.kt` |
| Camera callback bound to owner and attempt | `ui/ScreensQuest.kt` |
| Pending count and manual retry | `ui/SidequestsApp.kt` |

State lives under `noBackupFilesDir/lex_outbox_v1.json`. JPEGs remain in the
factory-created private files directory. Both are local storage, not a public
catalogue cache. File reads/writes run on Dispatchers.IO, using an atomic
replacement and fsync. Journey state and its events commit together. Local mutexes
serialize setup changes and photo snapshots. Do not manually edit this file.

WorkManager runs only with a network, uses exponential backoff starting at 30
seconds and serial work per owner. Network availability does not guarantee that
Supabase is reachable; failures leave work pending. Signing out pauses delivery
for that account; signing back in schedules it again. A force-stop can suppress
background work until the app is reopened. Scheduling is not an immediate timer.
Manual Retry replaces a delayed work chain so it does not wait behind the previous
backoff. An owner mutex keeps replacement workers from uploading simultaneously.

Each queued capture has a persistent proof UUID and stable Storage path. A retry
checks an existing registered record, or verifies an existing private JPEG after
a lost upload acknowledgement, before registering it. It never overwrites a
retake and never reports upload success before Storage and registration succeed.
An object is retained after registration failure so the same queued proof can be
retried. Missing local files require recapture. No new backend RLS policy is added;
the implementation keeps migration 009's private ownership rules.

Snapshots restore photo-bearing attempts that were left open; completed/abandoned
ones stay closed while their outstanding photos can still upload. This is not a
general offline queue for all quest lifecycle mutations. In particular, accept the
attempt online before testing offline photo capture so its backend row exists.
Offline reopening also requires a still-valid cached authentication session; this
change does not add offline sign-in or token refresh without a network.

## Physical-device test

1. Enable developer options and USB debugging on a physical Android phone, connect
   a data-capable USB cable, unlock it and authorize this computer's RSA prompt.
2. In Android Studio select the phone and run `app` from the work branch.
3. Sign in online and accept a quest with an actual photo-required step. Progress
   to that step using the current Supabase catalogue, not a guessed step index.
4. Disable Wi-Fi and mobile data. Capture and confirm a photograph. Expect local
   capture and a pending upload, never an uploaded indicator. A valid local capture
   still permits step completion.
5. Close and reopen the app while offline. The same owned attempt, file association
   and pending count should restore. The Quest tab opens that restored attempt.
6. Restore internet. Allow Android to run the queued work; Manual Retry is also
   available. Check the successful state and the record/object in Supabase.
7. Repeat Retry and reopen the app. The same proof must not acquire another
   `quest_photo_proofs` row. A deliberate new camera capture should create its own
   new proof and preserve the earlier one.
8. Sign out while work is pending and sign in as another account. It must not show
   or upload the previous owner's evidence. Return to the owner to resume.
9. Cancel the camera: no empty proof should enter the queue. Also check rotation
   and returning from the camera after an Activity recreation.

## BQ3 test

Use newly created test accounts. Completing a step means pressing Continue or
Start Exploring, not necessarily changing a default. Skip preserves selections
already made, while recording only the steps actually completed. Reopening resumes
the journey and choices; completing setup does not repeat it on every launch.
The first recommendation is frozen only after its Smart Picks card is composed
and loading ends. Refresh, catalogue browsing, another quest and an existing
attempt cannot manufacture another first acceptance.

The normal analytics horizon is 24 hours. The Analytics helper supports a clearly
labelled one-minute demonstration, including pending outcomes and inferred
inactivity exits. See the companion repository for formulas and expected cases.

## Evidence status and individual preparation

Local JVM and Python tests do not replace a real camera, background worker,
authenticated RLS, SQL job and dashboard test against the shared project.
Record those results separately; do not mark them validated before execution.
These changes provide concrete local-storage, eventual-connectivity and
concurrency decisions. Review the team's cache and the other Kotlin members'
strategies for the individual Viva. The shared analytics output is observational;
it does not prove that completing a step causes higher acceptance.

WorkManager reference:
https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work

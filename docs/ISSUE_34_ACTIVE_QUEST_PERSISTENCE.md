# Issue 34 — Active quest progress on-device

## Storage choice

Reuse the existing app-private `LexLocalStore` JSON file and its per-attempt `photoQuests` snapshot map. The store is already injected into `AppViewModel` by `AppViewModelFactory`; this change does not add another DataStore or duplicate the catalogue/preferences persistence work.

Each snapshot stores the authenticated `userId`, `questId`, `attemptId`, current step, completed-step indexes, and whether the attempt is closed. A snapshot is now written for every accepted active quest, not only attempts that have photo proofs. Step changes, saved exits, completion, abandonment, and rating closure update the snapshot.

## Restore and account isolation

After authentication, `attachAccount(userId)` restores only open snapshots belonging to that exact user. Switching or detaching accounts resets the in-memory UI state; another account cannot restore the previous user's attempt. Existing photo-proof metadata remains in the same store and continues to be applied after the active attempt is restored.

If the file is missing, the store starts empty. If it is malformed, decoding fails and the existing account-attachment error state is shown rather than silently displaying corrupt progress.

## Remote compatibility and boundaries

Supabase `QuestProgressRepository` calls are unchanged. Local snapshots make the active attempt available after process restart and while offline; remote updates continue through the existing repository when available. This issue does not add a retry queue, WorkManager job, or synchronization policy. Point 8 remains separate for coordination with the existing offline/retry work.

## Verification

Tests cover empty storage, snapshot serialization/recreation, malformed JSON, restoring a non-photo quest after ViewModel recreation, and account isolation.

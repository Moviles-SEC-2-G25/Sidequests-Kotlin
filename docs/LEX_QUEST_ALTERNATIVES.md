# Lex: contextual alternatives after abandonment

Branch: `feature/lex-contextual-quest-alternatives`.

## Individual contribution and behavior

Lex's extension connects the existing Exit Flow to a new Compose view. After a user chooses a reason and ends an active quest, the app offers up to three alternatives from the loaded catalogue. It explains why each option fits, and the user can view a quest before explicitly accepting it. Saving progress and keeping going do not trigger suggestions or abandonment.

`QuestAlternativeRecommender` owns this decision separately from the shared BQ5 recommendation engine. It excludes the source quest and other quests already touched in the session, enforces available time and budget, and requires a relevant improvement: shorter for lack of time, cheaper for cost, lower difficulty for difficulty, a venue-independent option for an unavailable place, or no travel/shorter known catalogue distance for distance. When no valid improvement exists, it returns an honest empty state.

Weather and time then affect ranking: wet weather favors no-travel or explicitly indoor activities and penalizes outdoor tags; clear weather favors outdoor tags; night favors indoor/no-travel options and penalizes outdoor trips. Morning/evening tags and interests also contribute. Rule codes and human-readable explanations accompany the result. Venue classification relies on catalogue tags; it is not a claim that a venue is open. Distance is a normalized catalogue label, not a GPS distance measurement.

This supports **context-aware** behavior because weather/time change the decision for the same reason and catalogue. It supports a **smart feature** as a deterministic recommendation system combining the abandonment reason, constraints, preferences and context. No machine-learning claim is made. Context acquisition continues to use the team's ContextManager, location and weather providers; Lex's contribution is the post-abandonment decision, state coordination, view and tests.

## Architecture and data

Compose renders the view; AppViewModel coordinates context and state; the pure domain engine ranks candidates; existing repositories persist attempts and analytics. BQ6's reason, attempt identifier and abandonment metadata are preserved. Suggestions never create an attempt automatically. Repeated taps cannot abandon the same active attempt twice. Leaving the suggestions view cancels its pending context request.

Context is read afresh for suggestions. Failed or timed-out context falls back to the current device time with unknown weather, which is shown explicitly. Existing catalogue fallback remains available. No backend migration is needed.

New events are `quest_alternative_shown` and `quest_alternative_selected`. Metadata includes algorithm version `lex_alternatives_v1`, source quest/attempt, reason, rank, score, applied rule codes, weather/time and context source. Selection means opening quest details, not accepting the quest. Debug scenarios are marked `simulated_debug`; normal input is marked `device`. No coordinates or image content are added to these events.

## Android Studio: first real run

1. Open the existing project and confirm the new branch. Sync Gradle after the test dependency change.
2. Run the app configuration on the emulator. Use the same Supabase account as before.
3. In Explore, use at least 30 available minutes. Accept Botanical Garden (30 min) or another quest with shorter alternatives in the remote catalogue.
4. Open Exit, select **Ran out of time**, then **Start another quest instead**. Expect **A better fit for now**, a context summary, up to three shorter alternatives, and explanations. Wait for the saving operation to finish.
5. Open **View quest**. No attempt is accepted until you press **Accept this quest** in details. The new acceptance gets a new attempt identifier.
6. Repeat with Coffee Crawl and **More expensive than expected**: alternatives must have estimated cost below 5 and fit time/budget. The exact options depend on the catalogue and preferences.
7. Separately use **Save progress & exit**: return to Explore, keep the same active attempt, and see no alternatives view or abandonment for that action.

Capture the new screen, its explanations and the new acceptance. The previous BQ6 rows 3/4 remain saved snapshots; new test abandonments increase future query totals.

## Reproducible weather/time comparison

Debug builds only. In Android Studio Terminal (PowerShell, repository root), before starting an attempt:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo-quest-alternatives.ps1 -Scenario rain
```

Use the same available time, preferences, source quest and reason for each scenario. End the source quest, wait for persistence, capture the alternatives, and return to Explore before restarting for another scenario. Restarting loses in-memory progress, so do this only between finished test attempts.

Run the same command with `-Scenario clear` and then `-Scenario night`. The screen must display **Demo context: simulated weather and time**. Rain is simulated at 14:00, clear at 14:00, night with clear weather at 23:00. Eligible sets may remain the same while ranking/explanations change; exact ranking changes require catalogue candidates affected differently by the rules. Unit tests prove these changes with a controlled identical catalogue.

Return to `-Scenario live` for actual context. Release builds ignore the scenario extras. The scenario never changes the existing context providers, BQ5 engine or BQ6 abandonment reason.

## Validation

Domain tests cover weather/time ranking changes, reason-specific improvements, time/budget/mode limits, exclusions, unknown distance/weather, empty results and deterministic ordering. Flow tests cover single abandonment, no automatic acceptance, separate save behavior, cancellation, fallback and explicit simulation metadata.

Build/tests: run `:app:testDebugUnitTest :app:assembleDebug`. Record the resulting test counts and the manual emulator results separately; an APK build does not by itself prove the real Supabase or camera flows.

Local verification on October 9, 2026: `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` completed successfully. All 29 tests passed, including 18 new tests for the engine, flow and debug-context parser. APK generation and Android lint succeeded.

After a manual run, execute `docs/LEX_ALTERNATIVES_VALIDATION.sql` in Supabase SQL Editor. Expect the correct source quest/attempt and reason, `source_attempt_status = abandoned`, matching `persisted_reason` and `linked_abandonment_event = true`. Live input is `device`; debug input is `simulated_debug`. Wait for network operations before interpreting missing records. The query is read-only and does not modify RLS or tables.

## Completed emulator and Supabase verification

On October 9, 2026, Lex performed the flow on the Android emulator and supplied the SQL Editor results. The normal-context run emitted two `quest_alternative_shown` events and one `quest_alternative_selected` event for Botanical Garden / `ran_out_of_time`. All three linked the correctly abandoned attempt and persisted reason. The run recorded device time as night and weather as unknown.

After explicitly accepting Camera Roll, a save-progress control confirmed a different attempt identifier, status `in_progress`, null abandonment reason, no abandonment event and no alternatives sourced from that saved attempt. This confirms persistence during the tested flow; recovery after a process restart was not tested.

Three debug-context runs used Botanical Garden / `ran_out_of_time`, 30 minutes, default preferences, and recorded `solo` / `all`. The screenshots and six Supabase events agreed:

| Controlled context | First suggestion | Score | Second suggestion | Score | Context rule |
| --- | --- | --- | --- | --- | --- |
| Rain, 14:00 | Camera Roll | 11 | Campus Mural Hunt | 7 | wet_no_travel |
| Clear, 14:00 | Campus Mural Hunt | 7 | Camera Roll | 7 | No additional context bonus |
| Clear, 23:00 | Camera Roll | 11 | Campus Mural Hunt | 7 | late_local |

All six events recorded `simulated_debug`, an abandoned source attempt, matching persisted reason and a linked abandonment event. The baseline score was 7 (shorter 4, social preference 1, difficulty preference 1, free 1). Rain or night added 4 to Camera Roll. With clear afternoon context, the tie was resolved by duration: 15 minutes before 20 minutes.

This demonstrates behavior changing with controlled weather and time inputs through the actual Compose/ViewModel/analytics flow. It does not validate live weather acquisition; the normal run exercised the unknown-weather fallback. Context providers remain the team's shared implementation, while Lex owns this alternative recommendation flow.

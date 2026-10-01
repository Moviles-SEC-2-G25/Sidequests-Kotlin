# Sidequests — Kotlin Android App

Native Android client for **Sidequests**, developed with Kotlin and Jetpack Compose.

This repository is the official Kotlin/Android client used by the Sidequests team for Sprint 2.

The validated Sprint 2 implementation is available in `main`. The repository also preserves the feature and integration branches used during development so the implementation history and individual contributions remain traceable.

---

## Sprint 2 Status

The official `main` branch has been:

- integrated from the validated Sprint 2 feature branches;
- reviewed through the subgroup integration process;
- validated by GitHub Actions;
- cloned from the official organization repository into a clean local directory;
- successfully built with `:app:assembleDebug`;
- executed and smoke-tested in the Android Studio emulator;
- validated against the shared Sidequests Supabase backend.

### Validated flows

The integrated Kotlin application has been tested with:

- Supabase authentication;
- remote quest catalogue;
- persistent quest lifecycle;
- BQ5 Smart Picks;
- `Not for me` recommendation exclusion;
- available-time and recommendation filters;
- GPS/location permission;
- Nearby and Anywhere modes;
- context-aware recommendations;
- Open-Meteo weather context;
- quest acceptance and active-quest navigation;
- camera photo proof;
- photo-required step completion gating;
- private Supabase Storage photo upload;
- upload retry behavior;
- structured analytics events for Sprint 2 Business Questions.

### Remaining validation

A final run on a physical Android device is tracked separately.

Emulator and clean-build validation should not be interpreted as proof of identical visual or sensor behavior on every physical Android device.

See [`docs/VALIDATION.md`](docs/VALIDATION.md) for the validation evidence and checklist.

---

# Implemented Sprint 2 Scope

## Authentication

The Kotlin client integrates with **Supabase Auth**.

Implemented flows include:

- account creation;
- sign-in;
- authenticated-session gating;
- sign-out from Profile.

Authentication state is exposed to the UI through observable application state.

---

## Remote Quest Catalogue

Quest data is loaded from the shared Supabase backend.

The Kotlin client includes:

- remote quest DTO mapping;
- remote catalogue loading;
- quest-step loading;
- local fallback behavior;
- catalogue refresh after authentication;
- remote/fallback source awareness in the UI.

---

## Quest Lifecycle and Progress

Quest progress is persisted through the shared backend.

Implemented lifecycle operations include:

- accept;
- start;
- step progress;
- abandon;
- complete;
- rating and feedback.

The backend owns lifecycle timestamps, while the client maintains the active attempt and synchronization state.

Structured lifecycle events are also emitted for Analytics.

---

# BQ5 — Smart Picks

The Kotlin client implements the runtime functionality associated with:

> **BQ5 — Personalized Quest Recommendation**

The client consumes the shared Supabase:

```text
public.recommend_quests
```

PostgreSQL RPC through PostgREST.

Recommendation logic is therefore shared in the backend instead of duplicated independently in Kotlin and Flutter.

The recommendation request can use information such as:

- available time;
- interests;
- preferred social interaction level;
- location mode;
- previous recommendation interactions;
- quest exclusions from the current session.

The UI displays up to three Smart Picks.

## `Not for me`

When the user selects **Not for me**:

1. the recommendation interaction is registered;
2. the quest is excluded from the current recommendation request;
3. Smart Picks are refreshed;
4. another candidate is displayed when one is available.

The normal Explore catalogue and filters remain available so the recommendation system assists the user rather than replacing their control over the catalogue.

---

# Context-Aware Functionality

The application includes context acquisition through:

- device location;
- time of day;
- Open-Meteo weather context.

The context flow is coordinated through `ContextManager`.

The application supports:

- Nearby/location-based mode;
- Anywhere/location-independent mode;
- location permission handling;
- context-aware recommendation behavior;
- fallback operation when optional location or weather information is unavailable.

Context information is also attached to relevant analytics evidence.

---

# Camera and Photo Proof

Quest steps that require photographic evidence use the Android camera flow.

Implemented behavior includes:

- full-resolution camera capture;
- one local photo proof per required quest step;
- private remote upload to Supabase Storage;
- upload retry;
- per-step proof registration;
- completion protection for photo-required steps.

A photo-required step cannot be marked complete until a usable local proof has been captured.

The integrated application was validated successfully uploading photo proof to the private:

```text
quest-proofs
```

Supabase Storage bucket.

---

# BQ6 Abandonment Evidence

The quest lifecycle emits structured evidence used by BQ6.

The abandonment event can include information such as:

- abandonment reason;
- quest duration;
- estimated cost;
- quest distance;
- distance band;
- current step;
- completed steps;
- total steps;
- progress percentage;
- photo-proof state.

The aggregation and Business Question processing are implemented in the separate `Sidequests-Analytics` repository.

---

# Architecture

The Kotlin application follows a compact MVVM-style reactive architecture:

```text
Jetpack Compose UI
        ↓
AppViewModel
        ↓
Repositories / domain services
        ↓
Supabase / Android sensors / external services
```

The main objective is to keep presentation logic separate from backend, persistence, analytics and device-specific behavior.

Important responsibilities include:

```text
SidequestsApp / Compose screens
        ↓
AppViewModel
        ├── SidequestsRepository
        ├── RecommendationRepository
        ├── AnalyticsRepository
        ├── QuestProgressRepository
        ├── PhotoProofRepository
        └── ContextManager
                ├── LocationProvider
                ├── WeatherService
                └── TimeProvider
```

Camera proof creation is separated through:

```text
CameraPhotoProofService
        ↓
PhotoProofFileFactory
        ↓
JpegPhotoProofFileFactory
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for additional architecture documentation.

---

# Sprint 2 Design-Pattern Evidence

## Sebastián Maldonado — Observer

The Kotlin presentation flow uses an Observer-style reactive architecture.

`AppViewModel` exposes observable `StateFlow` state.

Jetpack Compose consumes this state through lifecycle-aware state collection. When state changes in the ViewModel, observing composables receive the new value and recompose.

This separates application-state mutation from UI rendering.

Sebastián's Sprint 2 work also includes contributions to:

- Supabase integration;
- authentication;
- remote catalogue;
- persistent quest lifecycle;
- BQ5 Smart Picks;
- recommendation analytics;
- context evidence;
- photo-proof completion policy;
- integration and runtime validation.

---

## Julián Ramírez — Facade

`ContextManager` acts as a Facade over the context providers required by the application.

It provides a single context-facing abstraction over:

- location;
- weather;
- time of day.

Consumers therefore do not need to coordinate these providers independently.

Julián's Sprint 2 implementation also includes GPS/location context, Open-Meteo integration, location modes and BQ10-related mobile analytics integration.

---

## Lex Betancourt — Factory Method

The photo-proof flow implements a Factory-based file-creation abstraction through:

```text
PhotoProofFileFactory
JpegPhotoProofFileFactory
```

`CameraPhotoProofService` depends on the file-creation abstraction instead of directly owning all concrete JPEG file creation behavior.

Lex's Sprint 2 implementation also includes:

- full-resolution camera photo proof;
- private Supabase Storage upload;
- per-step proof state;
- structured BQ6 abandonment evidence;
- quest-distance evidence;
- unit-test and CI coverage for the photo-proof integration.

---

# User-Visible Flow

The application includes the main user-facing states required by the Sprint 2 prototype, including:

1. onboarding and preference collection;
2. Explore;
3. Smart Picks;
4. quest detail;
5. active quest;
6. quest step progress;
7. photo-required quest steps;
8. abandonment/exit flow;
9. rating and feedback;
10. group quest states;
11. profile/preferences;
12. contextual recommendation states.

See [`docs/MS7_VIEW_MAP.md`](docs/MS7_VIEW_MAP.md) for the detailed view mapping.

---

# Related Repositories

Sidequests uses separate repositories for the principal code and documentation responsibilities.

| Repository | Responsibility |
|---|---|
| `Sidequests-Kotlin` | Native Android Kotlin client |
| `Sidequests-Flutter` | Flutter mobile client |
| `Sidequests-Backend` | Shared Supabase schema, migrations, RPCs, Storage and backend contract |
| `Sidequests-Analytics` | Analytics pipeline and Business Question processing |
| `Wiki` | Sprint documentation and delivery evidence |

The Kotlin and Flutter clients share the same Supabase backend contract.

---

# Toolchain

The validated Kotlin build uses:

- Kotlin;
- Jetpack Compose;
- Android Gradle Plugin 9.1.1;
- Kotlin / Compose compiler 2.4.20;
- Compose BOM 2026.06.00;
- `compileSdk = 36`;
- `targetSdk = 36`;
- `minSdk = 26`;
- Java 17;
- Gradle 9.3.1.

---

# Local Setup

## 1. Requirements

Install:

- Android Studio;
- Android SDK 36;
- Java/JBR through Android Studio;
- Android emulator or Android device with API 26+.

Access to the shared Sidequests Supabase configuration is also required for backend-connected functionality.

---

## 2. Clone the Official Repository

```bash
git clone https://github.com/Moviles-SEC-2-G25/Sidequests-Kotlin.git
cd Sidequests-Kotlin
```

Use the validated Sprint 2 branch:

```bash
git checkout main
git pull
```

`main` is the final integrated Sprint 2 state.

---

## 3. Android SDK Configuration

Android Studio normally creates the local:

```text
local.properties
```

file automatically.

A typical Windows configuration is:

```properties
sdk.dir=C:/Users/<YOUR_USER>/AppData/Local/Android/Sdk
```

`local.properties` is machine-specific and must not be committed.

---

## 4. Supabase Configuration

Do not commit project configuration values directly into source files.

Place the shared Supabase configuration in the user-level Gradle properties file.

On Windows:

```text
C:\Users\<YOUR_USER>\.gradle\gradle.properties
```

Required properties:

```properties
SUPABASE_URL=...
SUPABASE_PUBLISHABLE_KEY=...
SUPABASE_QUEST_PROOFS_BUCKET=quest-proofs
```

A secret-free example is available in:

```text
gradle.properties.example
```

---

# Build

On Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

Optional clean build:

```powershell
.\gradlew.bat clean
.\gradlew.bat :app:assembleDebug
```

Expected result:

```text
BUILD SUCCESSFUL
```

The official repository was also validated by cloning it into a clean directory and successfully running this build from `main`.

---

# Run in Android Studio

1. Open the repository root in Android Studio.
2. Wait for Gradle Sync to finish.
3. Select the `app` run configuration.
4. Start an emulator or connect an Android device.
5. Run the application.
6. Sign in with a valid Supabase account or register and confirm a new account.

---

# Smoke-Test Checklist

For final validation, verify:

- [ ] Sign-up/sign-in works.
- [ ] Sign-out works.
- [ ] Remote Supabase quest catalogue loads.
- [ ] Smart Picks are displayed.
- [ ] Changing available time changes recommendation candidates when appropriate.
- [ ] `Not for me` excludes the selected recommendation.
- [ ] Nearby requests location permission.
- [ ] Anywhere works without requiring GPS.
- [ ] Live context can display Open-Meteo weather.
- [ ] Context fallback does not crash the Explore experience.
- [ ] A quest can be accepted.
- [ ] The active quest view becomes available.
- [ ] Normal steps can be completed.
- [ ] Photo-required steps block completion before capture.
- [ ] Camera capture creates local photo proof.
- [ ] Photo proof uploads successfully to Supabase Storage.
- [ ] Upload retry can recover from a failed upload.
- [ ] Quest lifecycle changes persist correctly.

---

# Validation Boundaries

The following distinctions are important when evaluating the Sprint 2 implementation:

- **Implemented:** functionality exists in the repository.
- **Tested:** automated or local tests were executed successfully.
- **Runtime-validated:** the integrated behavior was manually exercised in the Android emulator.
- **Physical-device validated:** requires execution on a real Android device.

The official Sprint 2 Kotlin build is implemented, tested and runtime-validated in the Android Studio emulator.

Physical-device validation is tracked separately and should not be claimed until it has been completed.

---

# Analytics

The Analytics Engine lives in:

```text
Sidequests-Analytics
```

Mobile clients emit events to the shared:

```text
public.analytics_events
```

table.

The Analytics Engine processes the implemented Business Questions and writes analytical outputs to the:

```text
analytics
```

schema.

Runtime recommendation for BQ5 is separate from analytics processing:

```text
Mobile client
    ↓
public.recommend_quests RPC
    ↓
Smart Picks
```

while BQ evidence follows:

```text
Mobile events
    ↓
public.analytics_events
    ↓
Analytics Engine
    ↓
analytics.bq_results
```

---

# Backend

The shared backend lives in:

```text
Sidequests-Backend
```

It contains the Supabase data model and migrations used by both mobile applications, including:

- quest catalogue;
- quest steps;
- user quest lifecycle;
- Row Level Security;
- recommendation RPC;
- analytics event contract;
- analytics output schema;
- private `quest-proofs` Storage configuration.

The Kotlin application does not require a separate Kotlin-specific backend.

---

# Sprint 2 Collaboration

Development was performed using feature branches, integration branches, commits, pull requests, peer reviews and GitHub Actions.

The final Kotlin state was consolidated through:

```text
feature branches
        ↓
integration/sprint2-kotlin
        ↓
main
```

The feature and integration history remains available in the official repository for traceability.

---

# Final Sprint 2 State

| Item | Status |
|---|---|
| Official Kotlin repository | ✅ |
| Sprint 2 code integrated into `main` | ✅ |
| Git history preserved | ✅ |
| GitHub Actions build | ✅ |
| Clean clone build | ✅ |
| Android emulator validation | ✅ |
| Supabase backend integration | ✅ |
| Supabase authentication | ✅ |
| Remote quest catalogue | ✅ |
| Persistent quest lifecycle | ✅ |
| BQ5 Smart Picks | ✅ |
| `Not for me` behavior | ✅ |
| GPS/context integration | ✅ |
| Open-Meteo integration | ✅ |
| Camera photo proof | ✅ |
| Supabase Storage upload | ✅ |
| Observer pattern evidence | ✅ |
| Facade pattern evidence | ✅ |
| Factory Method evidence | ✅ |

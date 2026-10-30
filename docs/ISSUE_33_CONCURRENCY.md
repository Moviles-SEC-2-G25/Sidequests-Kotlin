# Issue 33 — Concurrent context acquisition

## Implementation

`ContextManager.readContext()` starts the synchronous `TimeProvider.now()` call in a child coroutine while location acquisition is in progress. Both tasks run within the existing `Dispatchers.IO` context and a `coroutineScope`, so they share the caller's structured lifecycle.

Weather remains dependent on location: `getWeather(location)` is invoked only after `getCurrentLocation()` returns a non-null location. If location is unavailable, weather is skipped and the returned `UserContext` keeps both fields null. The existing time-of-day and timestamp mapping is unchanged.

## Why this is safe

- Only independent work (time and location acquisition) is overlapped.
- Weather is not requested until coordinates are available.
- `coroutineScope` keeps child work structured; cancellation and failures propagate instead of leaving detached tasks running.
- The existing `withContext(Dispatchers.IO)` boundary is retained, keeping blocking/provider work off the main thread.

## Verification

`ContextManagerTest` covers time being read while location is pending, weather starting only after location is available, missing location, and unavailable weather.

Source references:
- `app/src/main/java/com/sidequests/app/context/ContextManager.kt`
- `app/src/main/java/com/sidequests/app/context/LocationProvider.kt`
- `app/src/main/java/com/sidequests/app/context/WeatherService.kt`
- `app/src/main/java/com/sidequests/app/context/TimeProvider.kt`

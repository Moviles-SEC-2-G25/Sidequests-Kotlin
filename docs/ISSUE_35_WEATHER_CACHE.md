# Issue 35 — Open-Meteo weather response cache

The Open-Meteo provider now keeps a small in-memory LRU cache at the provider boundary.

- Cache key: latitude and longitude rounded to two decimal places (approximately kilometre-scale grouping).
- TTL: 10 minutes, measured from the successful fetch time.
- Capacity: three location keys; least-recently-used entries are evicted first.
- Fresh cache hits return without a network request.
- Expired or missing entries trigger a fetch. A cache entry is replaced only after a successful response.
- If refresh fails, the last successful value for that key is returned with `WeatherData.isStale = true`. If no prior value exists, the provider returns null.
- A mutex serializes cache reads and refreshes so concurrent requests for the same key do not trigger duplicate requests.

The cache is process-memory only. It is intentionally separate from Smart Picks (#23), image (#15), and reverse-geocoding (#30) caches. It does not implement an offline queue or retry mechanism.

Tests cover rounded-location hits, expiry, location changes, stale fallback, empty-cache failure, and LRU eviction.

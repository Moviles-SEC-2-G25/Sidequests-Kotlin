package com.sidequests.app.telemetry

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.sidequests.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * BQ1 (Type 1, app telemetry):
 * "At what specific software module does the app most frequently crash when a user brings the app
 * back to the foreground after tracking a location-based quest in the background for more than
 * 10 minutes?"
 *
 * - #21: [onAppForeground]/[onAppBackground] (driven by ProcessLifecycleOwner) detect the qualifying
 *   resumes and record a `bq1_resume_after_background` event (the denominator).
 * - #22: fatal crashes (default uncaught-exception handler) and non-fatal failures reported by the
 *   modules are recorded as `module_failure` events, tagged with the module and with whether they
 *   happened within 5 minutes of such a resume.
 *
 * Every event goes to the local SQLite queue first ([TelemetryStore]) and is uploaded by
 * [TelemetryUploadWorker] (WorkManager) when there is a connection.
 *
 * Design decisions (documented in the wiki):
 * - "Tracking a location-based quest in the background" = an accepted, unfinished Nearby (GPS)
 *   quest while the app is in the background. The app does not use background GPS (battery and
 *   extra permissions), so this is the observable equivalent.
 * - Failures are attributed to a resume when they happen within 5 minutes of it: the time the app
 *   needs to re-sync GPS, weather, catalogue and Smart Picks after coming back.
 * - Every failure records whether the phone was `online`, so the analysis can separate real bugs
 *   (failed with internet) from expected offline failures, and `app_version` to compare releases.
 * - Own pipeline instead of Crashlytics: Crashlytics only reports crashes and does not know about
 *   the resume context nor non-fatal failures per module; this keeps all BQs in the team's
 *   Supabase `analytics_events` table.
 */
object Bq1Telemetry : FailureReporter {
    const val EVENT_RESUME = "bq1_resume_after_background"
    const val EVENT_FAILURE = "module_failure"
    private const val UPLOAD_WORK = "bq1-telemetry-upload"
    private const val PREFS = "bq1_telemetry"
    private const val KEY_BACKGROUND_SINCE = "background_since"
    private const val KEY_BACKGROUND_QUEST = "background_quest"

    /** One id per app process, shared by all the events of this run. */
    val sessionId: String = UUID.randomUUID().toString()

    private val tracker = ResumeSessionTracker()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var appContext: Context? = null
    @Volatile private var store: TelemetryStore? = null

    /** Returns the id of the active Nearby (GPS) quest, or null. Set by MainActivity. */
    @Volatile var activeNearbyQuestProvider: () -> String? = { null }

    private val _pendingCount = MutableStateFlow(0L)
    val pendingCount: StateFlow<Long> = _pendingCount.asStateFlow()

    private val _lastResume = MutableStateFlow<ResumeInfo?>(null)
    val lastResume: StateFlow<ResumeInfo?> = _lastResume.asStateFlow()

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        store = TelemetryStore.getInstance(app)

        // The process may have been killed while the app was in the background.
        val prefs = prefs(app)
        tracker.restore(
            backgroundSinceMillis = prefs.getLong(KEY_BACKGROUND_SINCE, -1L).takeIf { it > 0 },
            questId = prefs.getString(KEY_BACKGROUND_QUEST, null),
        )

        installCrashHandler()
        refreshPendingCount()
        scheduleUpload() // flush anything left by a previous run (e.g. a fatal crash)
    }

    fun onAppBackground() {
        val now = System.currentTimeMillis()
        val questId = runCatching { activeNearbyQuestProvider() }.getOrNull()
        tracker.onBackground(now, questId)
        appContext?.let { prefs(it).edit().putLong(KEY_BACKGROUND_SINCE, now).putString(KEY_BACKGROUND_QUEST, questId).apply() }
    }

    fun onAppForeground() {
        val resume = tracker.onForeground(System.currentTimeMillis())
        appContext?.let { prefs(it).edit().remove(KEY_BACKGROUND_SINCE).remove(KEY_BACKGROUND_QUEST).apply() }
        if (resume != null) recordResume(resume)
    }

    /** Non-fatal failure reported by a module (network error, timeout, unavailable service...). */
    override fun report(module: AppModule, throwable: Throwable?, message: String?) {
        val now = System.currentTimeMillis()
        enqueue(EVENT_FAILURE, failureMetadata(module, fatal = false, throwable, message, now), now)
    }

    /** Debug/demo: records a qualifying resume without waiting 10 minutes. */
    fun simulateLongResume() {
        val questId = runCatching { activeNearbyQuestProvider() }.getOrNull() ?: "debug-no-active-quest"
        recordResume(tracker.simulateResume(System.currentTimeMillis(), 11 * 60 * 1000L, questId))
    }

    fun refreshPendingCount() {
        val currentStore = store ?: return
        scope.launch { _pendingCount.value = runCatching { currentStore.count() }.getOrDefault(0L) }
    }

    fun scheduleUpload() {
        val context = appContext ?: return
        val request = OneTimeWorkRequestBuilder<TelemetryUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UPLOAD_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun recordResume(resume: ResumeInfo) {
        _lastResume.value = resume
        enqueue(
            EVENT_RESUME,
            buildJsonObject {
                put("bq", "BQ1")
                put("app_version", BuildConfig.VERSION_NAME)
                isOnline()?.let { put("online", it) }
                put("background_minutes", resume.backgroundMillis / 60_000)
                put("quest_id", resume.questId)
                put("resume_at_millis", resume.resumedAtMillis) // links failures to this resume
            },
            resume.resumedAtMillis,
        )
    }

    private fun failureMetadata(
        module: AppModule,
        fatal: Boolean,
        throwable: Throwable?,
        message: String?,
        nowMillis: Long,
    ): JsonObject {
        val resume = tracker.activeResume(nowMillis)
        return buildJsonObject {
            put("bq", "BQ1")
            put("app_version", BuildConfig.VERSION_NAME)
            isOnline()?.let { put("online", it) }
            put("module", module.code)
            put("fatal", fatal)
            put("exception", throwable?.javaClass?.simpleName ?: "none")
            put("message", (message ?: throwable?.message ?: "").take(200))
            put("after_long_resume", resume != null)
            if (resume != null) {
                put("background_minutes", resume.backgroundMillis / 60_000)
                put("seconds_since_resume", (nowMillis - resume.resumedAtMillis) / 1000)
                put("resume_at_millis", resume.resumedAtMillis)
                put("quest_id", resume.questId)
            }
        }
    }

    private fun enqueue(eventType: String, metadata: JsonObject, createdAtMillis: Long) {
        val currentStore = store ?: return // not initialised (unit tests)
        scope.launch {
            runCatching { currentStore.insert(eventType, sessionId, metadata.toString(), createdAtMillis) }
            _pendingCount.value = runCatching { currentStore.count() }.getOrDefault(_pendingCount.value)
            scheduleUpload()
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val now = System.currentTimeMillis()
                val metadata = failureMetadata(ModuleClassifier.fromThrowable(throwable), fatal = true, throwable, null, now)
                // Synchronous on purpose: the process is about to die. Uploaded on the next start.
                store?.insert(EVENT_FAILURE, sessionId, metadata.toString(), now)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Validated internet at the moment of the event (null if it cannot be determined). */
    private fun isOnline(): Boolean? = runCatching {
        val context = appContext ?: return null
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrNull()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

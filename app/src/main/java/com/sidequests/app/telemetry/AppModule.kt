package com.sidequests.app.telemetry

/** BQ1: software modules used to answer "at which module does the app most frequently crash". */
enum class AppModule(val code: String) {
    AUTH("auth"),
    CATALOG("catalog"),
    RECOMMENDATIONS("recommendations"),
    CONTEXT("context"),
    GEOCODING("geocoding"),
    QUEST_PROGRESS("quest_progress"),
    PHOTO_PROOF("photo_proof"),
    SENSORS("sensors"),
    ANALYTICS("analytics"),
    UI("ui"),
    UNKNOWN("unknown"),
}

/** Something that can record a module failure (implemented by [Bq1Telemetry]; no-op in tests). */
fun interface FailureReporter {
    fun report(module: AppModule, throwable: Throwable?, message: String?)

    companion object {
        val NONE = FailureReporter { _, _, _ -> }
    }
}

/**
 * Maps a crash to the module where it happened, using the first stack frame that belongs to the
 * app (walking the cause chain). Package names follow the project structure.
 */
object ModuleClassifier {
    private const val ROOT = "com.sidequests.app."

    // Order matters: more specific prefixes first.
    private val rules: List<Pair<String, AppModule>> = listOf(
        "context.ReverseGeocoding" to AppModule.GEOCODING,
        "context." to AppModule.CONTEXT,
        "data.auth." to AppModule.AUTH,
        "ui.auth." to AppModule.AUTH,
        "auth." to AppModule.AUTH,
        "data.recommendation." to AppModule.RECOMMENDATIONS,
        "domain." to AppModule.RECOMMENDATIONS,
        "data.progress." to AppModule.QUEST_PROGRESS,
        "data.photo." to AppModule.PHOTO_PROOF,
        "sensor.camera." to AppModule.PHOTO_PROOF,
        "sensor." to AppModule.SENSORS,
        "data.analytics." to AppModule.ANALYTICS,
        "analytics." to AppModule.ANALYTICS,
        "telemetry." to AppModule.ANALYTICS,
        "data." to AppModule.CATALOG,
        "ui." to AppModule.UI,
        "MainActivity" to AppModule.UI,
        "SidequestsApplication" to AppModule.UI,
    )

    fun fromThrowable(throwable: Throwable): AppModule {
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = throwable
        while (current != null && seen.add(current)) {
            current.stackTrace.firstOrNull { it.className.startsWith(ROOT) }?.let {
                return fromClassName(it.className)
            }
            current = current.cause
        }
        return AppModule.UNKNOWN
    }

    fun fromClassName(className: String): AppModule {
        if (!className.startsWith(ROOT)) return AppModule.UNKNOWN
        val relative = className.removePrefix(ROOT)
        return rules.firstOrNull { (prefix, _) -> relative.startsWith(prefix) }?.second ?: AppModule.UNKNOWN
    }
}

package com.sidequests.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleClassifierTest {
    private fun crashIn(className: String): Throwable =
        RuntimeException("boom").apply {
            stackTrace = arrayOf(
                StackTraceElement("kotlinx.coroutines.DispatchedTask", "run", "DispatchedTask.kt", 1),
                StackTraceElement(className, "doWork", "File.kt", 10),
            )
        }

    @Test
    fun `first app frame decides the module`() {
        assertEquals(AppModule.GEOCODING, ModuleClassifier.fromThrowable(crashIn("com.sidequests.app.context.ReverseGeocodingServiceKt")))
        assertEquals(AppModule.CONTEXT, ModuleClassifier.fromThrowable(crashIn("com.sidequests.app.context.ContextManager")))
        assertEquals(AppModule.QUEST_PROGRESS, ModuleClassifier.fromThrowable(crashIn("com.sidequests.app.data.progress.SupabaseQuestProgressRepository")))
        assertEquals(AppModule.PHOTO_PROOF, ModuleClassifier.fromThrowable(crashIn("com.sidequests.app.sensor.camera.CameraPhotoProofService")))
        assertEquals(AppModule.UI, ModuleClassifier.fromThrowable(crashIn("com.sidequests.app.ui.ScreensQuestKt")))
    }

    @Test
    fun `cause chain is inspected and unknown code maps to UNKNOWN`() {
        val wrapped = RuntimeException("outer", crashIn("com.sidequests.app.data.recommendation.SupabaseRecommendationRepository")).apply {
            stackTrace = arrayOf(StackTraceElement("java.lang.Thread", "run", "Thread.java", 1))
        }
        assertEquals(AppModule.RECOMMENDATIONS, ModuleClassifier.fromThrowable(wrapped))
        assertEquals(AppModule.UNKNOWN, ModuleClassifier.fromThrowable(crashIn("okhttp3.Call")))
    }
}

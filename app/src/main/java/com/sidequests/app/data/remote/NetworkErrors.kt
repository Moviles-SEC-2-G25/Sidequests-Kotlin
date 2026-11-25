package com.sidequests.app.data.remote

import java.io.IOException

/**
 * Eventual connectivity: true when [this] (or one of its causes) is a network failure such as
 * "Unable to resolve host" or a timeout. Supabase wraps these in HttpRequestException, which is
 * an IOException. Raw messages of these errors are never shown to the user.
 */
fun Throwable.isNetworkFailure(): Boolean =
    generateSequence(this) { it.cause }.take(8).any { error ->
        error is IOException || error::class.simpleName?.contains("Timeout") == true
    }

const val OFFLINE_ACTION_MESSAGE = "No internet connection. Check your connection and try again."

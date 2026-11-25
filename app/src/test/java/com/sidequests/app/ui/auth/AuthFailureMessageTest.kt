package com.sidequests.app.ui.auth

import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AuthFailureMessageTest {
    @Test fun `no connection never shows the raw host error`() {
        val wrapped = IllegalStateException("request failed", UnknownHostException("Unable to resolve host \"x.supabase.co\""))
        val (title, body) = describeAuthFailure(wrapped, signUp = false)
        assertEquals("No connection", title)
        assertFalse(body.contains("resolve host"))
    }

    @Test fun `unknown failures get a friendly message for sign in and sign up`() {
        assertEquals("Couldn't sign you in", describeAuthFailure(IllegalStateException("boom"), signUp = false).first)
        assertEquals("Couldn't create your account", describeAuthFailure(IllegalStateException("boom"), signUp = true).first)
    }
}

package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.actions.DeviceActionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Jarvis", appName)
    }

    @Test
    fun `deviceActionManager creates valid URL response`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = DeviceActionManager(context)
        val result = manager.openUrl("google.com")
        assertNotNull(result)
        assertTrue(result.optBoolean("success", false))
        assertEquals("https://google.com", result.optString("url"))
    }

    @Test
    fun `deviceActionManager cleans phone number`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = DeviceActionManager(context)
        val result = manager.makeCall("+1 (555) 123-4567")
        assertNotNull(result)
        assertTrue(result.optBoolean("success", false))
        assertEquals("+15551234567", result.optString("phoneNumber"))
    }
}

// Exercises batch car-contact import through Android's real Contacts app.
package com.fghbuild.gomode.voice

import android.content.ContentUris
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.telephony.PhoneNumberUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.fghbuild.gomode.data.ServiceInstance
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.ui.settings.CarCallerIDSetting
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class CarCallerContactTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun oneButtonImportsGenericAndEverySavedServerContact() {
        val first =
            ServiceInstance("first", "Car, test; \\ ${UUID.randomUUID()}", url = "https://first.example/path?a=1&b=2")
        val second = ServiceInstance("second", "Other server ${UUID.randomUUID()}", url = "https://second.example")
        val settings =
            SettingsState(
                services = listOf(first, second, ServiceInstance("unallocated", "No number")),
                activeServiceId = first.id,
                carCallerSlots = mapOf(first.id to 1, second.id to 99, "retired" to 2),
                loadState = SettingsLoadState.Ready,
            )
        val expected = mapOf("+18675550100" to "Go Mode", "+18675550101" to first.label, "+18675550199" to second.label)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        assertEquals("1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        val resolver = instrumentation.targetContext.contentResolver
        val existing = mutableSetOf<Long>()
        val saved = mutableMapOf<String, String>()
        val importedIDs = mutableMapOf<String, Long>()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.READ_CONTACTS)
        try {
            resolver.query(Phone.CONTENT_URI, arrayOf(Phone.RAW_CONTACT_ID), null, null, null).use { cursor ->
                while (cursor != null && cursor.moveToNext()) existing += cursor.getLong(0)
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        composeRule.setContent {
            MaterialTheme { Column { CarCallerIDSetting(settings, enabled = true) } }
        }
        composeRule.onNodeWithText("Go Mode: +1-867-555-0100").assertTextEquals("Go Mode: +1-867-555-0100")
        composeRule
            .onNodeWithText(
                "${first.label}: +1-867-555-0101",
            ).assertTextEquals("${first.label}: +1-867-555-0101")
        composeRule
            .onNodeWithText(
                "${second.label}: +1-867-555-0199",
            ).assertTextEquals("${second.label}: +1-867-555-0199")
        try {
            composeRule.onNodeWithTag("gomode-car-create-contacts").performClick()
            // Some Contacts apps confirm the destination account before importing.
            val deadline = System.currentTimeMillis() + 20_000
            while (saved.size < expected.size && System.currentTimeMillis() < deadline) {
                device.findObject(By.text("Import"))?.click()
                device.findObject(By.text("Device"))?.click()
                device.findObject(By.res("com.android.permissioncontroller", "permission_allow_button"))?.click()
                instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.READ_CONTACTS)
                try {
                    resolver
                        .query(
                            Phone.CONTENT_URI,
                            arrayOf(Phone.NUMBER, Phone.DISPLAY_NAME, Phone.RAW_CONTACT_ID),
                            null,
                            null,
                            null,
                        ).use { cursor ->
                            while (cursor != null && cursor.moveToNext()) {
                                if (cursor.getLong(2) !in existing) {
                                    val number = PhoneNumberUtils.stripSeparators(cursor.getString(0))
                                    saved[number] = cursor.getString(1)
                                    importedIDs[number] = cursor.getLong(2)
                                }
                            }
                        }
                } finally {
                    instrumentation.uiAutomation.dropShellPermissionIdentity()
                }
                if (saved.size < expected.size) Thread.sleep(100)
            }
            assertEquals(expected, saved)
            val websites =
                mapOf(
                    "+18675550100" to "https://gomode.dev",
                    "+18675550101" to "https://first.example/path?a=1&b=2",
                    "+18675550199" to "https://second.example",
                )
            instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.READ_CONTACTS)
            try {
                for ((number, id) in importedIDs) {
                    val details = mutableMapOf<String, String>()
                    resolver
                        .query(
                            Data.CONTENT_URI,
                            arrayOf(Data.MIMETYPE, Data.DATA1),
                            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} IN (?, ?)",
                            arrayOf(id.toString(), Note.CONTENT_ITEM_TYPE, Website.CONTENT_ITEM_TYPE),
                            null,
                        ).use { cursor ->
                            while (cursor != null &&
                                cursor.moveToNext()
                            ) {
                                details[cursor.getString(0)] = cursor.getString(1)
                            }
                        }
                    assertEquals(
                        mapOf(
                            Note.CONTENT_ITEM_TYPE to "Created by Go Mode",
                            Website.CONTENT_ITEM_TYPE to websites[number],
                        ),
                        details,
                    )
                }
            } finally {
                instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
        } finally {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.WRITE_CONTACTS,
            )
            try {
                resolver
                    .query(
                        Phone.CONTENT_URI,
                        arrayOf(Phone.RAW_CONTACT_ID, Phone.NUMBER, Phone.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    ).use { cursor ->
                        while (cursor != null && cursor.moveToNext()) {
                            val id = cursor.getLong(0)
                            val number = PhoneNumberUtils.stripSeparators(cursor.getString(1))
                            if (id !in existing && expected[number] == cursor.getString(2)) {
                                resolver.delete(
                                    ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id),
                                    null,
                                    null,
                                )
                            }
                        }
                    }
            } finally {
                instrumentation.uiAutomation.dropShellPermissionIdentity()
                device.pressBack()
            }
        }
    }

    @Test
    fun contactSetupIsDisabledDuringVoice() {
        composeRule.setContent {
            MaterialTheme {
                Column { CarCallerIDSetting(SettingsState(loadState = SettingsLoadState.Ready), enabled = false) }
            }
        }
        composeRule.onNodeWithTag("gomode-car-create-contacts").assertIsNotEnabled()
    }
}

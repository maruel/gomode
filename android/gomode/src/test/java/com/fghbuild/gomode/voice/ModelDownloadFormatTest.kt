// Verifies download precision across small transfers, unit boundaries and locales.
package com.fghbuild.gomode.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class ModelDownloadFormatTest {
    @Test
    fun `progress uses one decimal place below one percent and through completion`() {
        val format = ModelDownloadFormat(Locale.US)
        assertEquals("0.4%", format.percent(10_000_000, 2_588_147_712))
        assertEquals("12.3%", format.percent(1234, 10_000))
        assertEquals("0.0%", format.percent(0, 100))
        assertEquals("100.0%", format.percent(100, 100))
        assertEquals("100.0%", format.percent(101, 100))
    }

    @Test
    fun `sizes round to three significant digits and promote rounded unit boundaries`() {
        val format = ModelDownloadFormat(Locale.US)
        assertEquals("10.0 MB", format.bytes(10_000_000).replace('\u00a0', ' '))
        assertEquals("1.79 GB", format.bytes(1_787_468_976).replace('\u00a0', ' '))
        assertEquals("1.00 MB", format.bytes(999_500).replace('\u00a0', ' '))
    }

    @Test
    fun `progress follows the device number locale`() {
        assertEquals("12,3 %", ModelDownloadFormat(Locale.FRANCE).percent(1234, 10_000).replace('\u00a0', ' '))
    }
}

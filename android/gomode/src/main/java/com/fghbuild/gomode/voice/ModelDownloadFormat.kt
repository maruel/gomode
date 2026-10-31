// Formats model download sizes and percentages for native UI and notifications.
package com.fghbuild.gomode.voice

import android.icu.number.NumberFormatter
import android.icu.number.Precision
import android.icu.util.MeasureUnit
import java.util.Locale

internal class ModelDownloadFormat(
    locale: Locale,
) {
    private val number = NumberFormatter.withLocale(locale).precision(Precision.fixedSignificantDigits(3))

    fun bytes(bytes: Long): String {
        var value = bytes.coerceAtLeast(0).toDouble()
        var index = 0
        // Promote before rounding 999.5 to 1,000 so the displayed unit stays compact.
        while (value >= 999.5 && index < units.lastIndex) {
            value /= 1000
            index++
        }
        return number.unit(units[index]).format(value).toString()
    }

    fun percent(
        bytes: Long,
        total: Long,
    ): String =
        number
            .precision(Precision.fixedFraction(1))
            .unit(MeasureUnit.PERCENT)
            .format((bytes.toDouble() / total).coerceIn(0.0, 1.0) * 100)
            .toString()

    companion object {
        private val units =
            listOf(
                MeasureUnit.BYTE,
                MeasureUnit.KILOBYTE,
                MeasureUnit.MEGABYTE,
                MeasureUnit.GIGABYTE,
                MeasureUnit.TERABYTE,
            )
    }
}

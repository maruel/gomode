// Tests shared speech-filter goldens, chunk independence, bounded streaming, and turn resets.
package com.fghbuild.gomode.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextFilterTest {
    @Test
    fun `shared goldens are independent of chunk boundaries`() {
        val source = checkNotNull(javaClass.getResource("/golden.json")).readText()
        for (entry in Json.parseToJsonElement(source).jsonArray) {
            val row = entry.jsonObject
            val name = row.getValue("name").jsonPrimitive.content
            val input = row.getValue("input").jsonPrimitive.content
            val expected = row.getValue("expected").jsonPrimitive.content
            for (split in 0..input.length) {
                val filter = SpeechTextFilter()
                assertEquals(
                    name,
                    expected,
                    filter.push(input.take(split)) + filter.push(input.drop(split)) + filter.finish(),
                )
            }
            val filter = SpeechTextFilter()
            assertEquals(name, expected, input.map { filter.push(it.toString()) }.joinToString("") + filter.finish())
            assertEquals("", filter.finish())
            assertEquals("Fresh", filter.push("Fresh") + filter.finish())
        }
    }

    @Test
    fun `long lines and labels stream without waiting for completion`() {
        for (prefix in listOf("", "[", "```text\n")) {
            val filter = SpeechTextFilter()
            filter.push(prefix)
            repeat(1000) { assertEquals("word ", filter.push("word ")) }
        }
        val filter = SpeechTextFilter()
        filter.push("[label](")
        repeat(1000) { assertEquals("", filter.push("destination")) }
        assertEquals(" Next", filter.push(") Next"))
    }

    @Test
    fun `clear discards every partial parser state`() {
        val filter = SpeechTextFilter()
        for (partial in listOf("#", "1.", "_", "`", "```kotlin", "[label]", "[label](hidden", "``code")) {
            filter.push(partial)
            filter.clear()
            assertEquals("New", filter.push("**New**") + filter.finish())
        }
    }
}

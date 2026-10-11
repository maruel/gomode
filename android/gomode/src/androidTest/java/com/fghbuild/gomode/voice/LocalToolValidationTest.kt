// Verifies JSON Schema resources, reference resolution, and exact numeric validation on Android.
package com.fghbuild.gomode.voice

import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalToolValidationTest {
    @Test
    fun advertisedSchemaRejectsQueryAndChecksReferencedExactNumbersOnAndroid() {
        val schema =
            Json.parseToJsonElement(
                """
                {"${'$'}schema":"https://json-schema.org/draft/2020-12/schema",
                 "${'$'}defs":{"task":{"type":"integer","minimum":1,"maximum":9007199254740992}},
                 "properties":{"task":{"${'$'}ref":"#/${'$'}defs/task"},
                   "ratio":{"type":"number","maximum":0.1234567890123456788},
                   "selection":{"type":["array","object"],"uniqueItems":true}},
                 "required":["task"],"additionalProperties":false}
                """.trimIndent(),
            )
        val validator = ToolCallValidator(listOf(ToolDescriptor(name = "example", inputSchema = schema)))
        assertNull(validator.validate("example", Json.parseToJsonElement("""{"task":6}""").jsonObject))
        assertNotNull(validator.validate("example", Json.parseToJsonElement("""{"query":"task 6"}""").jsonObject))
        assertNull(
            validator.validate(
                "example",
                Json.parseToJsonElement("""{"task":6,"selection":{"from":6,"to":6}}""").jsonObject,
            ),
        )
        assertNotNull(
            validator.validate("example", Json.parseToJsonElement("""{"task":6,"selection":[6,6.0]}""").jsonObject),
        )
        assertNotNull(
            validator.validate("example", Json.parseToJsonElement("""{"task":9007199254740993}""").jsonObject),
        )
        assertNotNull(
            validator.validate(
                "example",
                Json.parseToJsonElement("""{"task":6,"ratio":0.1234567890123456789}""").jsonObject,
            ),
        )
    }
}

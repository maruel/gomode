// Tests advertised tool-schema constraints, exact numbers, dialects, and fail-closed schema preparation.
package com.fghbuild.gomode.voice

import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallValidatorTest {
    private fun validator(schema: String): ToolCallValidator =
        ToolCallValidator(listOf(ToolDescriptor(name = "example", inputSchema = Json.parseToJsonElement(schema))))

    private fun validate(
        validator: ToolCallValidator,
        arguments: String,
    ): String? = validator.validate("example", Json.parseToJsonElement(arguments).jsonObject)

    @Test
    fun requiredFieldsTypesAndExtraFieldsRejectTheReportedQueryWithoutCoercion() {
        val validator =
            validator(
                """
                {"type":"object","properties":{"task":{"type":"integer","minimum":1}},
                 "required":["task"],"additionalProperties":false}
                """.trimIndent(),
            )
        assertNull(validate(validator, """{"task":6}"""))
        assertNull(validate(validator, """{"task":6.0}"""))
        for (args in listOf(
            "{}",
            """{"query":"task 6"}""",
            """{"task":"6"}""",
            """{"task":true}""",
            """{"task":null}""",
            """{"task":0}""",
            """{"task":6.5}""",
            """{"task":6,"query":"private"}""",
        )) {
            assertNotNull(args, validate(validator, args))
        }
        assertNotNull(validator.validate("unknown", Json.parseToJsonElement("{}").jsonObject))
    }

    @Test
    fun additionalPropertyPolicyIsNotSilentlyTightened() {
        assertNull(validate(validator("{}"), """{"query":"allowed"}"""))
        assertNull(validate(validator("""{"additionalProperties":true}"""), """{"query":"allowed"}"""))
        val typedExtras = validator("""{"additionalProperties":{"type":"integer"}}""")
        assertNull(validate(typedExtras, """{"count":2}"""))
        assertNotNull(validate(typedExtras, """{"count":"2"}"""))
    }

    @Test
    fun nestedObjectsArraysEnumsAndStringConstraintsAreEnforced() {
        val validator =
            validator(
                """
                {"type":"object","properties":{"options":{"type":"object","properties":{
                  "mode":{"enum":["fast","safe"]},"label":{"type":"string","minLength":2,"maxLength":4,"pattern":"^[a-z]+$"},
                  "ids":{"type":"array","minItems":1,"maxItems":2,"uniqueItems":true,"items":{"type":"integer","minimum":1}},
                  "note":{"type":["null","string"]}},"required":["mode","label","ids"],"additionalProperties":false}},
                 "required":["options"],"additionalProperties":false}
                """.trimIndent(),
            )
        assertNull(validate(validator, """{"options":{"mode":"safe","label":"ok","ids":[1,2],"note":null}}"""))
        for (options in listOf(
            """{"mode":"wrong","label":"ok","ids":[1]}""",
            """{"mode":"safe","label":"x","ids":[1]}""",
            """{"mode":"safe","label":"longer","ids":[1]}""",
            """{"mode":"safe","label":"OK","ids":[1]}""",
            """{"mode":"safe","label":"ok","ids":[]}""",
            """{"mode":"safe","label":"ok","ids":[1,2,3]}""",
            """{"mode":"safe","label":"ok","ids":[1,1]}""",
            """{"mode":"safe","label":"ok","ids":[0]}""",
            """{"mode":"safe","label":"ok","ids":[1],"query":true}""",
        )) {
            assertNotNull(options, validate(validator, """{"options":$options}"""))
        }
    }

    @Test
    fun localReferencesCompositionAndPatternPropertiesKeepTheirSchemaSemantics() {
        val validator =
            validator(
                """
                {"${'$'}defs":{"task":{"oneOf":[{"type":"integer","minimum":1},
                  {"type":"string","pattern":"^#[1-9][0-9]*$"}]}},
                 "properties":{"task":{"${'$'}ref":"#/${'$'}defs/task"}},"required":["task"],
                 "patternProperties":{"^flag_":{"type":"boolean"}},"additionalProperties":false}
                """.trimIndent(),
            )
        assertNull(validate(validator, """{"task":"#6","flag_ready":true}"""))
        assertNull(validate(validator, """{"task":6}"""))
        assertNotNull(validate(validator, """{"task":"6"}"""))
        assertNotNull(validate(validator, """{"task":6,"flag_ready":"true"}"""))
        val conditional =
            validator(
                """
                {"if":{"properties":{"mode":{"const":"send"}},"required":["mode"]},
                 "then":{"required":["message"]},"else":{"not":{"required":["message"]}},
                 "allOf":[{"anyOf":[{"required":["mode"]},{"required":["task"]}]}]}
                """.trimIndent(),
            )
        assertNull(validate(conditional, """{"mode":"send","message":"Hello"}"""))
        assertNotNull(validate(conditional, """{"mode":"send"}"""))
        assertNotNull(validate(conditional, """{"task":6,"message":"unexpected"}"""))
        assertNotNull(validate(conditional, "{}"))
    }

    @Test
    fun numericBoundsAndMultiplesUseExactDecimalAndLargeIntegerValues() {
        val validator =
            validator(
                """
                {"properties":{"id":{"maximum":9007199254740992},
                 "ratio":{"maximum":0.1234567890123456788},"step":{"multipleOf":0.01}},"additionalProperties":false}
                """.trimIndent(),
            )
        assertNull(validate(validator, """{"id":9007199254740992,"ratio":0.1234567890123456788,"step":0.03}"""))
        assertNotNull(validate(validator, """{"id":9007199254740993}"""))
        assertNotNull(validate(validator, """{"ratio":0.1234567890123456789}"""))
        assertNotNull(validate(validator, """{"step":0.031}"""))
    }

    @Test
    fun integerMultiplesAndIntegralDecimalsBeyondLongDoNotLosePrecision() {
        val multiples = validator("""{"properties":{"id":{"type":"integer","multipleOf":2}}}""")
        assertNull(validate(multiples, """{"id":9007199254740992}"""))
        assertNotNull(validate(multiples, """{"id":9007199254740993}"""))
        val bounded = validator("""{"properties":{"id":{"type":"integer","maximum":100}}}""")
        assertNull(validate(bounded, """{"id":100.0}"""))
        assertNotNull(validate(bounded, """{"id":18446744073709551617.0}"""))
    }

    @Test
    fun recursiveNumericEqualityIgnoresIntegerAndDecimalRepresentation() {
        val unique = validator("""{"properties":{"items":{"uniqueItems":true}}}""")
        assertNotNull(validate(unique, """{"items":[6,6.0]}"""))
        assertNotNull(validate(unique, """{"items":[{"id":6},{"id":6.0}]}"""))
        assertNull(validate(unique, """{"items":[9007199254740992,9007199254740993]}"""))
        val enum = validator("""{"properties":{"item":{"enum":[{"id":6}]}}}""")
        assertNull(validate(enum, """{"item":{"id":6.0}}"""))
        assertNotNull(validate(enum, """{"item":{"id":7}}"""))
        val constant = validator("""{"properties":{"item":{"const":[6]}}}""")
        assertNull(validate(constant, """{"item":[6.0]}"""))
    }

    @Test
    fun countsOutsideTheValidatorIntegerRangeFailClosedInsteadOfWrapping() {
        for (keyword in listOf("maxItems", "maxLength", "maxProperties", "minItems", "minLength", "minProperties")) {
            assertTrue(keyword, runCatching { validator("""{"$keyword":4294967296}""") }.isFailure)
        }
    }

    @Test
    fun uniqueItemsAppliesOnlyToArraysAcrossDialects() {
        for (dialect in listOf(null, "http://json-schema.org/draft-07/schema#")) {
            val declaration = dialect?.let { "\"\$schema\":\"$it\"," }.orEmpty()
            val validator =
                validator(
                    """{$declaration"properties":{"selection":{"type":["array","object"],"uniqueItems":true}}}""",
                )
            assertNull(validate(validator, """{"selection":{"from":6,"to":6}}"""))
            assertNull(validate(validator, """{"selection":[6,7]}"""))
            assertNotNull(validate(validator, """{"selection":[6,6.0]}"""))
        }
    }

    @Test
    fun underflowingMultipleOfCannotBeSilentlyIgnored() {
        assertTrue(
            runCatching { validator("""{"properties":{"step":{"multipleOf":1e-400}}}""") }.isFailure,
        )
    }

    @Test
    fun explicitDraftSevenFormatAssertionsDifferFromDefaultAnnotations() {
        val schema = """{"properties":{"email":{"type":"string","format":"email"}}}"""
        assertNull(validate(validator(schema), """{"email":"not-an-email"}"""))
        val draftSeven =
            validator(
                """{"${'$'}schema":"http://json-schema.org/draft-07/schema#",${schema.drop(1)}""",
            )
        assertNotNull(validate(draftSeven, """{"email":"not-an-email"}"""))
        assertNull(validate(draftSeven, """{"email":"owner@example.com"}"""))
    }

    @Test
    fun missingMalformedAndUnresolvableSchemasCannotEnableDispatch() {
        assertTrue(runCatching { ToolCallValidator(listOf(ToolDescriptor(name = "missing"))) }.isFailure)
        for (schema in listOf(
            """{"type":"imaginary"}""",
            """{"required":"task"}""",
            """{"${'$'}ref":"#/missing"}""",
            """{"${'$'}ref":"https://127.0.0.1:1/private-schema"}""",
            """{"${'$'}schema":"https://127.0.0.1:1/private-dialect"}""",
        )) {
            assertTrue(schema, runCatching { validator(schema) }.isFailure)
        }
        val tool = ToolDescriptor(name = "duplicate", inputSchema = Json.parseToJsonElement("{}"))
        assertTrue(runCatching { ToolCallValidator(listOf(tool, tool)) }.isFailure)
    }

    @Test
    fun externalReferencesDoNotMakeNetworkRequests() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            server.start()
            val schema = """{"${'$'}ref":"${server.url("/private-schema")}"}"""
            assertTrue(runCatching { validator(schema) }.isFailure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun validationDoesNotRewriteArguments() {
        val validator = validator("""{"properties":{"task":{"type":"integer"}}}""")
        val arguments = Json.parseToJsonElement("""{"task":6.0,"note":null,"ratio":0.1234567890123456789}""").jsonObject
        val before = arguments.toString()
        assertNull(validator.validate("example", arguments))
        assertEquals(before, arguments.toString())
    }
}

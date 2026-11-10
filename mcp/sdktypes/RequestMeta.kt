// MCP request metadata preserves typed fields and arbitrary extension keys.
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

@Serializable(with = RequestMetaSerializer::class)
data class RequestMeta(
    val protocolVersion: String,
    val clientInfo: Implementation,
    val clientCapabilities: ClientCapabilities,
    val progressToken: JsonElement? = null,
    val deprecatedLogLevel: String? = null,
    val locale: String? = null,
    val organization: String? = null,
    val session: String? = null,
    val subject: String? = null,
    val userAgent: String? = null,
    val userLocation: OpenAIUserLocation? = null,
    val extra: Map<String, JsonElement> = emptyMap(),
)

object RequestMetaSerializer : KSerializer<RequestMeta> {
    private const val protocol = "io.modelcontextprotocol/protocolVersion"
    private const val client = "io.modelcontextprotocol/clientInfo"
    private const val capabilities = "io.modelcontextprotocol/clientCapabilities"
    private const val logLevel = "io.modelcontextprotocol/logLevel"
    private const val location = "openai/userLocation"
    private val optionalStrings = listOf(logLevel, "openai/locale", "openai/organization", "openai/session", "openai/subject", "openai/userAgent")
    private val known = optionalStrings.toSet() + setOf(protocol, client, capabilities, location, "progressToken")
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): RequestMeta {
        val input = decoder as JsonDecoder
        val obj = input.decodeJsonElement().jsonObject
        fun string(key: String): String? {
            val value = obj[key] ?: return null
            val primitive = value as? JsonPrimitive ?: throw SerializationException("$key must be a string")
            if (!primitive.isString) throw SerializationException("$key must be a string")
            return primitive.content
        }
        return RequestMeta(
            protocolVersion = string(protocol) ?: throw SerializationException("missing $protocol"),
            clientInfo = input.json.decodeFromJsonElement(Implementation.serializer(), obj[client] ?: throw SerializationException("missing $client")),
            clientCapabilities = input.json.decodeFromJsonElement(ClientCapabilities.serializer(), obj[capabilities] ?: throw SerializationException("missing $capabilities")),
            progressToken = obj["progressToken"], deprecatedLogLevel = string(logLevel), locale = string("openai/locale"),
            organization = string("openai/organization"), session = string("openai/session"), subject = string("openai/subject"),
            userAgent = string("openai/userAgent"),
            userLocation = obj[location]?.let { input.json.decodeFromJsonElement(OpenAIUserLocation.serializer(), it) },
            extra = obj.filterKeys { it !in known },
        )
    }

    override fun serialize(encoder: Encoder, value: RequestMeta) {
        val output = encoder as JsonEncoder
        val obj = buildJsonObject {
            value.extra.filterKeys { it !in known }.forEach { (key, entry) -> put(key, entry) }
            put(protocol, JsonPrimitive(value.protocolVersion))
            put(client, output.json.encodeToJsonElement(Implementation.serializer(), value.clientInfo))
            put(capabilities, output.json.encodeToJsonElement(ClientCapabilities.serializer(), value.clientCapabilities))
            value.progressToken?.let { put("progressToken", it) }
            listOf(value.deprecatedLogLevel, value.locale, value.organization, value.session, value.subject, value.userAgent).forEachIndexed { index, entry ->
                if (!entry.isNullOrEmpty()) put(optionalStrings[index], JsonPrimitive(entry))
            }
            value.userLocation?.let { if (!it.timezone.isNullOrEmpty()) put(location, output.json.encodeToJsonElement(OpenAIUserLocation.serializer(), it)) }
        }
        output.encodeJsonElement(obj)
    }
}

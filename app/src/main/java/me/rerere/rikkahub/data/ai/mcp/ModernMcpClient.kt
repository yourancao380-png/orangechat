/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

internal const val MODERN_MCP_PROTOCOL_VERSION = "2026-07-28"

internal sealed interface ModernMcpContent {
    data class Text(val text: String) : ModernMcpContent
    data class Image(val data: String, val mimeType: String) : ModernMcpContent
    data class Unknown(val value: JsonElement) : ModernMcpContent
}

/**
 * Minimal stateless MCP 2026-07-28 client for the tool surface used by OrangeChat.
 *
 * The official Kotlin SDK currently exposes the new discovery types but does not yet provide a
 * complete dual-era client. Keeping this adapter small lets the existing SDK client remain the
 * fallback for handshake-era servers while modern servers avoid initialize/session/GET entirely.
 */
internal class ModernMcpClient(
    private val httpClient: OkHttpClient,
    private val url: String,
    private val clientName: String,
    private val headers: List<Pair<String, String>>,
) : Closeable {
    private val nextRequestId = AtomicLong(1L)

    /** Returns false only when the endpoint clearly does not understand modern discovery. */
    suspend fun discover(): Boolean = withContext(Dispatchers.IO) {
        val response = execute("server/discover")
        if (response is RpcResponse.Unsupported) return@withContext false
        val result = (response as RpcResponse.Success).result
        val supportedVersions = result["supportedVersions"] as? JsonArray
        supportedVersions?.any {
            (it as? JsonPrimitive)?.contentOrNull == MODERN_MCP_PROTOCOL_VERSION
        } == true
    }

    suspend fun listTools(): List<McpTool> = withContext(Dispatchers.IO) {
        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        do {
            val extra = buildJsonObject {
                cursor?.let { put("cursor", it) }
            }
            val result = (execute("tools/list", extraParams = extra) as RpcResponse.Success).result
            result["tools"]?.jsonArray?.forEach { element ->
                val tool = element.jsonObject
                val inputSchema = tool["inputSchema"] as? JsonObject
                tools += McpTool(
                    name = tool["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    description = tool["description"]?.jsonPrimitive?.contentOrNull,
                    inputSchema = inputSchema?.toInputSchema(),
                )
            }
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
        } while (!cursor.isNullOrBlank())
        tools.filter { it.name.isNotBlank() }
    }

    suspend fun callTool(name: String, arguments: JsonObject): List<ModernMcpContent> =
        withContext(Dispatchers.IO) {
            val result = (execute(
                method = "tools/call",
                name = name,
                extraParams = buildJsonObject {
                    put("name", name)
                    put("arguments", arguments)
                },
            ) as RpcResponse.Success).result

            if (result["resultType"]?.jsonPrimitive?.contentOrNull == "input_required") {
                // OrangeChat does not yet render an MRTR interaction form. Preserve the complete
                // request so the model/user can see what the server asked for instead of losing it.
                return@withContext listOf(ModernMcpContent.Unknown(result))
            }

            val content = result["content"] as? JsonArray
            if (content == null) {
                val structured = result["structuredContent"]
                return@withContext if (structured != null && structured !is JsonNull) {
                    listOf(ModernMcpContent.Unknown(structured))
                } else {
                    emptyList()
                }
            }
            content.map { element ->
                val item = element as? JsonObject
                    ?: return@map ModernMcpContent.Unknown(element)
                when (item["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> ModernMcpContent.Text(
                        item["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    )
                    "image" -> ModernMcpContent.Image(
                        data = item["data"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        mimeType = item["mimeType"]?.jsonPrimitive?.contentOrNull
                            ?: "application/octet-stream",
                    )
                    else -> ModernMcpContent.Unknown(element)
                }
            }
        }

    override fun close() = Unit // The shared OkHttp client is owned by McpManager.

    private fun execute(
        method: String,
        name: String? = null,
        extraParams: JsonObject = JsonObject(emptyMap()),
    ): RpcResponse {
        val id = nextRequestId.getAndIncrement()
        val params = buildJsonObject {
            extraParams.forEach { (key, value) -> put(key, value) }
            put("_meta", buildJsonObject {
                put("io.modelcontextprotocol/protocolVersion", MODERN_MCP_PROTOCOL_VERSION)
                put("io.modelcontextprotocol/clientInfo", buildJsonObject {
                    put("name", clientName.ifBlank { "OrangeChat" })
                    put("version", "1.0")
                })
                put("io.modelcontextprotocol/clientCapabilities", buildJsonObject {})
            })
        }
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val request = Request.Builder()
            .url(url)
            .apply {
                headers.forEach { (key, value) -> header(key, value) }
            }
            .post(McpJson.encodeToString(JsonObject.serializer(), payload)
                .toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", MODERN_MCP_PROTOCOL_VERSION)
            .header("Mcp-Method", method)
            .apply {
                name?.let { header("Mcp-Name", it) }
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (response.code in setOf(400, 404, 405, 406, 415, 422) && method == "server/discover") {
                return RpcResponse.Unsupported
            }
            if (!response.isSuccessful) {
                throw ModernMcpException(response.code, rawBody.ifBlank { response.message })
            }
            val envelope = runCatching { parseModernMcpEnvelope(rawBody) }
                .getOrElse {
                    if (method == "server/discover") return RpcResponse.Unsupported
                    throw ModernMcpException(response.code, "Invalid JSON-RPC response", it)
                }
            envelope["error"]?.let { error ->
                if (method == "server/discover") return RpcResponse.Unsupported
                throw ModernMcpException(response.code, error.toString())
            }
            val result = envelope["result"] as? JsonObject
                ?: if (method == "server/discover") return RpcResponse.Unsupported
                else throw ModernMcpException(response.code, "JSON-RPC response has no result")
            return RpcResponse.Success(result)
        }
    }

    private sealed interface RpcResponse {
        data class Success(val result: JsonObject) : RpcResponse
        data object Unsupported : RpcResponse
    }
}

internal fun parseModernMcpEnvelope(rawBody: String): JsonObject {
    runCatching { return McpJson.parseToJsonElement(rawBody).jsonObject }

    // Streamable HTTP may choose text/event-stream for a POST response. Reassemble each SSE event
    // according to the data-field rules and use the final JSON-RPC envelope in the response.
    val envelopes = mutableListOf<JsonObject>()
    val data = StringBuilder()
    fun dispatch() {
        if (data.isBlank()) return
        runCatching { McpJson.parseToJsonElement(data.toString()).jsonObject }
            .onSuccess(envelopes::add)
        data.clear()
    }
    rawBody.lineSequence().forEach { line ->
        when {
            line.isEmpty() -> dispatch()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substringAfter("data:").removePrefix(" "))
            }
        }
    }
    dispatch()
    return envelopes.lastOrNull() ?: throw IllegalArgumentException("No JSON-RPC envelope in response")
}

internal class ModernMcpException(
    val statusCode: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("Modern MCP HTTP $statusCode: $message", cause)

private fun JsonObject.toInputSchema(): InputSchema {
    val properties = this["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val required = (this["required"] as? JsonArray)?.mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull
    }
    return InputSchema.Obj(properties = properties, required = required)
}

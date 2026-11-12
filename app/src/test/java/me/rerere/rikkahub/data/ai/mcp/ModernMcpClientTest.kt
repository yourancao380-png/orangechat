/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ModernMcpClientTest {
    @Test
    fun `parses regular json rpc envelope`() {
        val envelope = parseModernMcpEnvelope(
            """{"jsonrpc":"2.0","id":1,"result":{"supportedVersions":["2026-07-28"]}}"""
        )

        assertEquals("1", envelope["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parses final envelope from sse body`() {
        val envelope = parseModernMcpEnvelope(
            """
            event: message
            data: {"jsonrpc":"2.0","id":1,
            data: "result":{"tools":[]}}

            """.trimIndent()
        )

        assertEquals("[]", envelope["result"]?.jsonObject?.get("tools")?.toString())
    }
}

/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HardlineCommandGuardTest {
    @Test
    fun `scans namespaced MCP arguments`() {
        val input = buildJsonObject {
            put("command", "rm -rf /")
        }

        assertNotNull(HardlineCommandGuard.checkToolParsed("mcp_12ab34cd_shell", input))
    }

    @Test
    fun `allows ordinary MCP text`() {
        val input = buildJsonObject {
            put("query", "find orange cats")
        }

        assertNull(HardlineCommandGuard.checkToolParsed("mcp_12ab34cd_search", input))
    }
}

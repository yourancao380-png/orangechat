/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.provider.providers.openai

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAIEndpointTest {
    @Test
    fun `joins base and path with one slash`() {
        assertEquals(
            "https://relay.example/v1/chat/completions",
            openAIEndpoint("https://relay.example/v1/", "/chat/completions"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects blank base url`() {
        openAIEndpoint(" ", "/chat/completions")
    }
}

/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPlannerTest {
    @Test
    fun `valid sidecar replaces only covered prefix in request`() {
        val rawMessages = messages("one", "two", "three", "four")
        val summary = createContextSummary("one and two", rawMessages.take(2))

        val planned = rawMessages.planContext(summary, messageLimit = 0)

        assertEquals(rawMessages.drop(2), planned.messages)
        assertEquals("one and two", planned.summary)
        assertEquals(4, rawMessages.size)
    }

    @Test
    fun `stale sidecar is ignored after source edit`() {
        val rawMessages = messages("one", "two", "three")
        val summary = createContextSummary("one and two", rawMessages.take(2))
        val editedFirst = rawMessages.first().copy(parts = listOf(UIMessagePart.Text("edited")))
        val persisted = listOf(editedFirst, rawMessages[1], rawMessages[2])

        val planned = persisted.planContext(summary, messageLimit = 0)

        assertEquals(persisted, planned.messages)
        assertEquals(null, planned.summary)
    }

    @Test
    fun `message limit is applied after sidecar planning`() {
        val rawMessages = messages("one", "two", "three", "four")
        val summary = createContextSummary("one and two", rawMessages.take(2))

        val planned = rawMessages.planContext(summary, messageLimit = 1)

        assertEquals(listOf(rawMessages.last()), planned.messages)
        assertEquals("one and two", planned.summary)
    }

    @Test
    fun `checkpoint fingerprint changes when source content changes`() {
        val source = messages("one", "two")
        val edited = source.toMutableList().apply {
            this[1] = this[1].copy(parts = listOf(UIMessagePart.Text("changed")))
        }

        assertFalse(source.contextFingerprint() == edited.contextFingerprint())
    }

    private fun messages(vararg texts: String): List<UIMessage> = texts.mapIndexed { index, text ->
        UIMessage(
            role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(text)),
        )
    }
}

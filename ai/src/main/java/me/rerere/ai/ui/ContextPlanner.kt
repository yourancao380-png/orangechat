/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.ui

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private val contextFingerprintJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

@Serializable
data class ContextSummary(
    val content: String,
    val sourceStartMessageId: Uuid,
    val sourceEndMessageId: Uuid,
    val sourceMessageCount: Int,
    val sourceFingerprint: String,
    val formatVersion: Int = 1,
)

data class PlannedContext(
    val messages: List<UIMessage>,
    val summary: String? = null,
)

/**
 * Builds a stable fingerprint for messages covered by a context summary.
 */
fun List<UIMessage>.contextFingerprint(): String {
    val bytes = contextFingerprintJson.encodeToString(this).encodeToByteArray()
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/**
 * Creates sidecar metadata for a summary without modifying the source messages.
 */
fun createContextSummary(
    summary: String,
    sourceMessages: List<UIMessage>,
): ContextSummary {
    require(sourceMessages.isNotEmpty()) { "A context summary needs at least one source message" }
    return ContextSummary(
        content = summary.trim(),
        sourceStartMessageId = sourceMessages.first().id,
        sourceEndMessageId = sourceMessages.last().id,
        sourceMessageCount = sourceMessages.size,
        sourceFingerprint = sourceMessages.contextFingerprint(),
    )
}

/**
 * Selects request context while keeping the persisted conversation untouched.
 *
 * A valid sidecar summary replaces only its covered prefix in the outgoing request. If the source
 * prefix changed, the summary is ignored and the original messages are used. The summary text is
 * returned separately so providers receive it inside the single primary system prompt.
 */
fun List<UIMessage>.planContext(
    contextSummary: ContextSummary?,
    messageLimit: Int,
): PlannedContext {
    if (contextSummary == null || contextSummary.formatVersion != 1) {
        return PlannedContext(messages = limitContext(messageLimit))
    }

    val endIndex = indexOfFirst { it.id == contextSummary.sourceEndMessageId }
    val sourcePrefix = if (endIndex >= 0) subList(0, endIndex + 1) else emptyList()
    val isValid = sourcePrefix.firstOrNull()?.id == contextSummary.sourceStartMessageId &&
        sourcePrefix.size == contextSummary.sourceMessageCount &&
        sourcePrefix.contextFingerprint() == contextSummary.sourceFingerprint

    return if (isValid) {
        PlannedContext(
            messages = drop(endIndex + 1).limitContext(messageLimit),
            summary = contextSummary.content,
        )
    } else {
        PlannedContext(messages = limitContext(messageLimit))
    }
}

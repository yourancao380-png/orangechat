/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.provider.providers.openai

internal fun openAIEndpoint(baseUrl: String, path: String): String {
    require(baseUrl.isNotBlank()) { "OpenAI base URL cannot be blank" }
    require(path.isNotBlank()) { "OpenAI endpoint path cannot be blank" }
    return "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
}

package dev.firstlightui.plugins.local_ai

import org.json.JSONArray
import org.json.JSONObject

class LocalAIFailure(val code: String, override val message: String) : Exception(message)

data class LocalAIMessage(val role: String, val content: String)

data class LocalAIRequest(
    val id: String,
    val instructions: String,
    val messages: List<LocalAIMessage>,
    val temperature: Float?,
    val maxTokens: Int?,
) {
    companion object {
        /**
         * NativePHP's Android bridge decodes the top-level parameters with
         * JSONObject, so nested values arrive as JSONArray and JSONObject,
         * not as Kotlin lists and maps. Accept both.
         */
        fun from(parameters: Map<String, Any>): LocalAIRequest {
            val id = (parameters["id"] as? String)?.takeIf { it.isNotEmpty() }
                ?: throw LocalAIFailure("invalid_request", "id is required")
            val raw = list(parameters["messages"])?.takeIf { it.isNotEmpty() }
                ?: throw LocalAIFailure("invalid_request", "messages are required")
            val messages = raw.map { item ->
                val role = field(item, "role")
                val content = field(item, "content")
                if ((role != "user" && role != "assistant") || content.isNullOrEmpty()) {
                    throw LocalAIFailure("invalid_request", "messages must have a user or assistant role and content")
                }
                LocalAIMessage(role, content)
            }
            if (messages.last().role != "user") {
                throw LocalAIFailure("invalid_request", "the last message must come from the user")
            }
            val maxTokens = (parameters["maxTokens"] as? Number)?.toInt()
            if (maxTokens != null && maxTokens < 1) {
                throw LocalAIFailure("invalid_request", "maxTokens must be positive")
            }
            return LocalAIRequest(
                id = id,
                instructions = parameters["instructions"] as? String ?: "",
                messages = messages,
                temperature = (parameters["temperature"] as? Number)?.toFloat(),
                maxTokens = maxTokens,
            )
        }

        private fun list(value: Any?): List<Any?>? = when (value) {
            is JSONArray -> (0 until value.length()).map { value.opt(it) }
            is List<*> -> value
            else -> null
        }

        private fun field(item: Any?, key: String): String? = when (item) {
            is JSONObject -> item.opt(key) as? String
            is Map<*, *> -> item[key] as? String
            else -> null
        }
    }
}

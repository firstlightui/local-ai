package dev.firstlightui.plugins.local_ai

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Content
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.nativephp.mobile.ui.nativerender.NativeElementBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        fun from(parameters: Map<String, Any>): LocalAIRequest {
            val id = (parameters["id"] as? String)?.takeIf { it.isNotEmpty() }
                ?: throw LocalAIFailure("invalid_request", "id is required")
            val raw = (parameters["messages"] as? List<*>)?.takeIf { it.isNotEmpty() }
                ?: throw LocalAIFailure("invalid_request", "messages are required")
            val messages = raw.map { item ->
                val map = item as? Map<*, *>
                val role = map?.get("role") as? String
                val content = map?.get("content") as? String
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
    }
}

private class LocalAIOperation(val request: LocalAIRequest) {
    var status = "queued"
    val text = StringBuilder()
    var sequence = 0
    var errorCode = ""
    var errorMessage = ""
    var cancelRequested = false
    var job: Job? = null

    val isTerminal get() = status == "completed" || status == "failed" || status == "cancelled"
}

/**
 * Process-scoped owner of Gemini Nano generation: one FIFO queue, one active
 * generation, and in-memory snapshots that survive activity recreation.
 */
object LocalAIRuntime {
    private const val MAX_PENDING = 8
    private const val MAX_RETAINED_TERMINAL = 32

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val operations = HashMap<String, LocalAIOperation>()
    private val pending = ArrayDeque<String>()
    private val terminalOrder = ArrayDeque<String>()
    private var activeId: String? = null
    private var backgrounded = false
    private var downloadJob: Job? = null

    private val model: GenerativeModel by lazy { Generation.getClient() }

    fun availability(): Map<String, Any> {
        val status = try {
            runBlocking(Dispatchers.IO) { withTimeout(5_000) { model.checkStatus() } }
        } catch (e: Exception) {
            return mapOf("status" to "unavailable", "reason" to "status_check_failed", "platform" to "android")
        }
        return when (status) {
            FeatureStatus.AVAILABLE -> mapOf("status" to "available", "reason" to "", "platform" to "android")
            FeatureStatus.DOWNLOADABLE -> mapOf("status" to "downloadable", "reason" to "", "platform" to "android")
            FeatureStatus.DOWNLOADING -> mapOf("status" to "downloading", "reason" to "", "platform" to "android")
            else -> mapOf("status" to "unavailable", "reason" to "device_not_eligible", "platform" to "android")
        }
    }

    fun download(): Map<String, Any> {
        val current = availability()
        if (current["status"] != "downloadable") return current

        synchronized(lock) {
            if (downloadJob?.isActive == true) {
                return current + ("status" to "downloading")
            }
            downloadJob = scope.launch {
                try {
                    model.download().collect { status ->
                        when (status) {
                            is DownloadStatus.DownloadStarted -> emit("ModelDownloadProgress", mapOf(
                                "status" to "started", "bytesToDownload" to status.bytesToDownload,
                            ))
                            is DownloadStatus.DownloadProgress -> emit("ModelDownloadProgress", mapOf(
                                "status" to "downloading", "bytesDownloaded" to status.totalBytesDownloaded,
                            ))
                            is DownloadStatus.DownloadCompleted -> emit("ModelDownloadProgress", mapOf(
                                "status" to "completed",
                            ))
                            is DownloadStatus.DownloadFailed -> emit("ModelDownloadProgress", mapOf(
                                "status" to "failed", "message" to "The model download failed.",
                            ))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emit("ModelDownloadProgress", mapOf("status" to "failed", "message" to "The model download failed."))
                }
            }
        }
        return current + ("status" to "downloading")
    }

    fun submit(request: LocalAIRequest) {
        if (availability()["status"] != "available") {
            throw LocalAIFailure("unavailable", "The on-device model is not available.")
        }
        synchronized(lock) {
            if (operations.containsKey(request.id)) {
                throw LocalAIFailure("duplicate_id", "An operation with this id already exists.")
            }
            if (pending.size >= MAX_PENDING) {
                throw LocalAIFailure("busy", "Too many generations are queued.")
            }
            operations[request.id] = LocalAIOperation(request)
            pending.addLast(request.id)
            startNextLocked()
        }
    }

    fun cancel(id: String) {
        val job: Job?
        synchronized(lock) {
            val operation = operations[id]
                ?: throw LocalAIFailure("unknown_operation", "No operation has this id.")
            if (operation.isTerminal) return
            operation.cancelRequested = true
            if (operation.status == "queued") {
                pending.remove(id)
                val text = finishLocked(operation, "cancelled")
                emit("GenerationCancelled", mapOf("id" to id, "text" to text))
                return
            }
            job = operation.job
        }
        job?.cancel()
    }

    fun snapshot(id: String): Map<String, Any> = synchronized(lock) {
        val operation = operations[id]
            ?: throw LocalAIFailure("unknown_operation", "No operation has this id; it expired or the app restarted.")
        mapOf(
            "id" to id,
            "status" to operation.status,
            "text" to operation.text.toString(),
            "sequence" to operation.sequence,
            "errorCode" to operation.errorCode,
            "errorMessage" to operation.errorMessage,
        )
    }

    fun setBackgrounded(value: Boolean) = synchronized(lock) {
        backgrounded = value
        startNextLocked()
    }

    private fun startNextLocked() {
        if (activeId != null || backgrounded || pending.isEmpty()) return
        val operation = operations[pending.removeFirst()] ?: return
        activeId = operation.request.id
        operation.status = "running"
        operation.job = scope.launch { run(operation) }
    }

    private suspend fun run(operation: LocalAIOperation) {
        try {
            model.generateContentStream(buildRequest(operation.request)).collect { chunk ->
                val delta = chunk.candidates.firstOrNull()?.text.orEmpty()
                if (delta.isNotEmpty()) receive(operation, delta)
            }
            complete(operation, "completed")
        } catch (e: CancellationException) {
            complete(operation, "cancelled")
        } catch (e: Exception) {
            if (synchronized(lock) { operation.cancelRequested }) {
                complete(operation, "cancelled")
            } else {
                val failure = failure(e)
                complete(operation, "failed", failure.code, failure.message)
            }
        }
    }

    private suspend fun buildRequest(request: LocalAIRequest): GenerateContentRequest {
        if (request.instructions.isNotEmpty() && !model.isSystemPromptAvailable()) {
            throw LocalAIFailure("unsupported", "This device's model does not support system instructions.")
        }
        val contents = request.messages.map { message ->
            Content.builder()
                .setRole(if (message.role == "user") Content.Role.USER else Content.Role.MODEL)
                .text(message.content)
                .build()
        }
        val builder = GenerateContentRequest.Builder(contents)
        if (request.instructions.isNotEmpty()) {
            builder.systemInstruction = SystemInstruction(request.instructions)
        }
        request.temperature?.let { builder.temperature = it }
        request.maxTokens?.let { builder.maxOutputTokens = it }
        return builder.build()
    }

    private fun receive(operation: LocalAIOperation, delta: String) {
        val sequence = synchronized(lock) {
            if (operation.status != "running" || operation.cancelRequested) return
            operation.text.append(delta)
            operation.sequence += 1
            operation.sequence
        }
        emit("TextDeltaGenerated", mapOf("id" to operation.request.id, "sequence" to sequence, "delta" to delta))
    }

    private fun complete(operation: LocalAIOperation, status: String, code: String = "", message: String = "") {
        val text: String
        synchronized(lock) {
            if (operation.isTerminal) return
            operation.errorCode = code
            operation.errorMessage = message
            text = finishLocked(operation, status)
            activeId = null
            startNextLocked()
        }
        val id = operation.request.id
        when (status) {
            "completed" -> emit("GenerationCompleted", mapOf("id" to id, "text" to text))
            "cancelled" -> emit("GenerationCancelled", mapOf("id" to id, "text" to text))
            else -> emit("GenerationFailed", mapOf("id" to id, "code" to code, "message" to message, "text" to text))
        }
    }

    private fun finishLocked(operation: LocalAIOperation, status: String): String {
        operation.status = status
        operation.job = null
        terminalOrder.addLast(operation.request.id)
        while (terminalOrder.size > MAX_RETAINED_TERMINAL) {
            operations.remove(terminalOrder.removeFirst())
        }
        return operation.text.toString()
    }

    private fun failure(e: Exception): LocalAIFailure {
        if (e is LocalAIFailure) return e
        if (e !is GenAiException) return LocalAIFailure("generation_failed", "Generation failed.")
        val message = e.message ?: "Generation failed."
        val code = when (e.errorCode) {
            GenAiException.ErrorCode.REQUEST_TOO_LARGE -> "context_exceeded"
            GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR,
            GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR,
            GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR -> "guardrail"
            GenAiException.ErrorCode.BUSY,
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> "rate_limited"
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> "background_blocked"
            GenAiException.ErrorCode.NOT_AVAILABLE,
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> "unavailable"
            else -> "generation_failed"
        }
        return LocalAIFailure(code, message)
    }

    private fun emit(event: String, payload: Map<String, Any?>) {
        NativeElementBridge.sendNativeEvent(
            "FirstlightUI\\LocalAI\\Events\\$event",
            JSONObject(payload).toString(),
        )
    }
}

fun initializeLocalAI(context: Context) {
    (context.applicationContext as Application).registerActivityLifecycleCallbacks(
        object : Application.ActivityLifecycleCallbacks {
            private var startedActivities = 0

            override fun onActivityStarted(activity: Activity) {
                startedActivities += 1
                if (startedActivities == 1) LocalAIRuntime.setBackgrounded(false)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0 && !activity.isChangingConfigurations) {
                    LocalAIRuntime.setBackgrounded(true)
                }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
    )
}

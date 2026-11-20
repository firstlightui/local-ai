package dev.firstlightui.plugins.local_ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Content
import com.google.mlkit.genai.prompt.Feature
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
            // A stream that ends normally after a cancel request still counts as cancelled.
            val cancelled = synchronized(lock) { operation.cancelRequested }
            complete(operation, if (cancelled) "cancelled" else "completed")
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
        if (request.instructions.isNotEmpty() && Feature.SYSTEM_PROMPT !in model.getAvailableFeatures()) {
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
        if (e !is GenAiException) return LocalAIFailure("generation_failed", e.message ?: "Generation failed.")
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

/**
 * Pauses the queue while the whole app is in the background. NativePHP calls
 * this from a background thread while MainActivity boots, possibly after the
 * activity has started, so it observes the process lifecycle, which reports
 * the current state to a late observer, rather than counting activity starts.
 */
fun initializeLocalAI(@Suppress("UNUSED_PARAMETER") context: Context) {
    Handler(Looper.getMainLooper()).post {
        ProcessLifecycleOwner.get().lifecycle.addObserver(LocalAILifecycleObserver)
    }
}

private object LocalAILifecycleObserver : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) = LocalAIRuntime.setBackgrounded(false)

    override fun onStop(owner: LifecycleOwner) = LocalAIRuntime.setBackgrounded(true)
}

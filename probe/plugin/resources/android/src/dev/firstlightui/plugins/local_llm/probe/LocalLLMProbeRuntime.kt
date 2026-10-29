package dev.firstlightui.plugins.local_llm.probe

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.nativephp.mobile.ui.nativerender.NativeElementBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class ProbeRequest(
    val id: String,
    val modelPath: String,
    val mode: String,
    val prompt: String,
    val systemPrompt: String?,
    val maxTokens: Int,
) {
    companion object {
        fun from(parameters: Map<String, Any>): ProbeRequest {
            val id = parameters["id"] as? String
                ?: throw IllegalArgumentException("id is required")
            val modelPath = parameters["modelPath"] as? String
                ?: throw IllegalArgumentException("modelPath is required")
            val mode = parameters["mode"] as? String ?: "text"
            require(mode == "text" || mode == "chat") { "mode must be text or chat" }

            var prompt = parameters["prompt"] as? String
            var systemPrompt: String? = null
            if (mode == "chat") {
                @Suppress("UNCHECKED_CAST")
                val messages = parameters["messages"] as? List<Map<String, Any>>
                    ?: throw IllegalArgumentException("messages are required for chat")
                require(messages.isNotEmpty()) { "messages cannot be empty" }
                require(messages.count { it["role"] == "user" } == 1) {
                    "the Android feasibility probe accepts one user turn"
                }
                require(messages.all { it["role"] == "system" || it["role"] == "user" }) {
                    "the Android feasibility probe accepts only system and user roles"
                }
                systemPrompt = messages.firstOrNull { it["role"] == "system" }?.get("content") as? String
                prompt = messages.single { it["role"] == "user" }["content"] as? String
            }

            require(!prompt.isNullOrEmpty()) { "prompt cannot be empty" }
            return ProbeRequest(
                id = id,
                modelPath = modelPath,
                mode = mode,
                prompt = prompt,
                systemPrompt = systemPrompt,
                maxTokens = ((parameters["maxTokens"] as? Number)?.toInt() ?: 128).coerceIn(1, 512),
            )
        }
    }
}

object LocalLLMProbeRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = Channel<ProbeRequest>(Channel.UNLIMITED)
    private val backgrounded = MutableStateFlow(false)
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var context: Context? = null
    @Volatile private var activeId: String? = null
    @Volatile private var engine: InferenceEngine? = null
    @Volatile private var loadedPath: String? = null
    @Volatile private var unloadRequested = false

    init {
        scope.launch {
            for (request in requests) {
                backgrounded.first { value -> !value }
                if (cancelled.remove(request.id)) {
                    emitTerminal(request.id, "cancelled", phase = "queued")
                    continue
                }
                run(request)
            }
        }
    }

    fun initialize(value: Context) {
        context = value.applicationContext
    }

    fun submit(request: ProbeRequest) {
        check(requests.trySend(request).isSuccess) { "probe queue is unavailable" }
    }

    fun cancel(id: String) {
        cancelled += id
        if (activeId == id) {
            engine?.requestCancel()
        }
    }

    fun setBackgrounded(value: Boolean) {
        backgrounded.value = value
    }

    fun unloadWhenIdle() {
        unloadRequested = true
        if (activeId == null) scope.launch { releaseModel() }
    }

    private suspend fun run(request: ProbeRequest) {
        activeId = request.id
        try {
            val resolvedModelPath = resolveModelPath(request.modelPath)
            val inference = engine ?: AiChat.getInferenceEngine(
                checkNotNull(context) { "probe runtime has not been initialized" }
            ).also { created ->
                engine = created
                created.state.first {
                    it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
                }
                check(created.state.value is InferenceEngine.State.Initialized) {
                    "llama.cpp failed to initialize"
                }
            }

            if (loadedPath != resolvedModelPath) {
                if (loadedPath != null) inference.cleanUp()
                emitTerminal(request.id, "running", phase = "loading")
                inference.loadModel(resolvedModelPath)
                loadedPath = resolvedModelPath
            }

            if (cancelled.remove(request.id)) {
                throw CancellationException("generation cancelled after loading")
            }
            if (request.mode == "chat" && request.systemPrompt != null) {
                inference.setSystemPrompt(request.systemPrompt)
            }
            if (cancelled.remove(request.id)) {
                throw CancellationException("generation cancelled during system prompt")
            }
            emitTerminal(request.id, "running", phase = "prompt")

            var sequence = 0
            val output = StringBuilder()
            inference.sendPrompt(
                request.prompt,
                request.maxTokens,
                request.mode == "chat",
            ).collect { delta ->
                sequence += 1
                output.append(delta)
                emitDelta(request.id, sequence, delta)
            }

            if (cancelled.remove(request.id)) {
                throw CancellationException("generation cancelled")
            }
            emitTerminal(request.id, "completed", text = output.toString())
        } catch (_: CancellationException) {
            emitTerminal(request.id, "cancelled", phase = "running")
        } catch (error: Throwable) {
            emitTerminal(request.id, "failed", error = error.message ?: error.javaClass.simpleName)
        } finally {
            cancelled.remove(request.id)
            releaseModel()
            activeId = null
        }
    }

    private fun resolveModelPath(path: String): String {
        val candidate = File(path)
        return if (candidate.isAbsolute) {
            candidate.path
        } else {
            File(checkNotNull(context) { "probe runtime has not been initialized" }.filesDir, path).path
        }
    }

    private fun releaseModel() {
        if (loadedPath != null) engine?.cleanUp()
        loadedPath = null
        unloadRequested = false
    }

    private fun emitDelta(id: String, sequence: Int, delta: String) {
        emit("FirstlightUI\\LocalLLM\\Probe\\Events\\ProbeDelta", mapOf(
            "id" to id,
            "sequence" to sequence,
            "delta" to delta,
        ))
    }

    private fun emitTerminal(
        id: String,
        status: String,
        text: String? = null,
        phase: String? = null,
        error: String? = null,
    ) {
        emit("FirstlightUI\\LocalLLM\\Probe\\Events\\ProbeTerminal", mapOf(
            "id" to id,
            "status" to status,
            "text" to text,
            "phase" to phase,
            "error" to error,
        ))
    }

    private fun emit(event: String, payload: Map<String, Any?>) {
        NativeElementBridge.sendNativeEvent(event, JSONObject(payload).toString())
    }
}

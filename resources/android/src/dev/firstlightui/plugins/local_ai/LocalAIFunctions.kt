package dev.firstlightui.plugins.local_ai

import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse

object LocalAIFunctions {
    class Availability(@Suppress("UNUSED_PARAMETER") activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> =
            respond { LocalAIRuntime.availability() }
    }

    class Download(@Suppress("UNUSED_PARAMETER") activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> =
            respond { LocalAIRuntime.download() }
    }

    class Generate(@Suppress("UNUSED_PARAMETER") activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> = respond {
            val request = LocalAIRequest.from(parameters)
            LocalAIRuntime.submit(request)
            mapOf("id" to request.id, "status" to "queued")
        }
    }

    class Cancel(@Suppress("UNUSED_PARAMETER") activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> = respond {
            val id = id(parameters)
            LocalAIRuntime.cancel(id)
            mapOf("id" to id, "requested" to true)
        }
    }

    class Status(@Suppress("UNUSED_PARAMETER") activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> =
            respond { LocalAIRuntime.snapshot(id(parameters)) }
    }

    private fun id(parameters: Map<String, Any>): String =
        (parameters["id"] as? String)?.takeIf { it.isNotEmpty() }
            ?: throw LocalAIFailure("invalid_request", "id is required")

    private inline fun respond(body: () -> Map<String, Any>): Map<String, Any> = try {
        BridgeResponse.success(body())
    } catch (e: LocalAIFailure) {
        BridgeResponse.error(e.code, e.message)
    } catch (e: Exception) {
        BridgeResponse.error("bridge_failed", "Local AI request failed.")
    }
}

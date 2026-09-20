package com.nativephp.localai.probe

import android.content.Context
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse

object LocalAIProbeFunctions {
    class Start(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val request = ProbeRequest.from(parameters)
            LocalAIProbeRuntime.initialize(context.applicationContext)
            LocalAIProbeRuntime.submit(request)
            return BridgeResponse.success(mapOf("id" to request.id, "accepted" to true))
        }
    }

    class Cancel(@Suppress("UNUSED_PARAMETER") context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String
                ?: throw IllegalArgumentException("id is required")
            LocalAIProbeRuntime.cancel(id)
            return BridgeResponse.success(mapOf("id" to id, "requested" to true))
        }
    }

    class Unload(@Suppress("UNUSED_PARAMETER") context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            LocalAIProbeRuntime.unloadWhenIdle()
            return BridgeResponse.success(mapOf("accepted" to true))
        }
    }

    class SetBackgrounded(@Suppress("UNUSED_PARAMETER") context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val backgrounded = parameters["backgrounded"] as? Boolean
                ?: throw IllegalArgumentException("backgrounded is required")
            LocalAIProbeRuntime.setBackgrounded(backgrounded)
            return BridgeResponse.success(mapOf("backgrounded" to backgrounded))
        }
    }
}

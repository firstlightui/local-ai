// Stand-ins for NativePHP host types, used only by the hostCheck compile. Signatures copied from
// nativephp/mobile 4.8 resources/androidstudio/.../bridge/BridgeRouter.kt.
// Keep them in sync when upgrading NativePHP; a real device build is the final check.
package com.nativephp.mobile.bridge

interface BridgeFunction {
    fun execute(parameters: Map<String, Any>): Map<String, Any>
}

object BridgeResponse {
    fun success(data: Map<String, Any> = emptyMap()): Map<String, Any> = data

    fun error(code: String, message: String, data: Map<String, Any> = emptyMap()): Map<String, Any> =
        mapOf("status" to "error", "code" to code, "message" to message, "data" to data)
}

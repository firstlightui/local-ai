package dev.firstlightui.plugins.local_ai

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LocalAIRequestTest {
    /** Decodes parameters the way nativephp/mobile's BridgeRouter.nativePHPCall does. */
    private fun bridgeParameters(json: String): Map<String, Any> {
        val parameters = mutableMapOf<String, Any>()
        val obj = JSONObject(json)
        obj.keys().forEach { parameters[it] = obj.get(it) }
        return parameters
    }

    @Test
    fun `parses the payload PHP sends through the bridge`() {
        val request = LocalAIRequest.from(bridgeParameters(
            """{"id":"op-1","instructions":"Be brief.","messages":[{"role":"user","content":"Hi"},{"role":"assistant","content":"Hello."},{"role":"user","content":"Name a colour."}],"temperature":0.7,"maxTokens":64}"""
        ))

        assertEquals("op-1", request.id)
        assertEquals("Be brief.", request.instructions)
        assertEquals(
            listOf(
                LocalAIMessage("user", "Hi"),
                LocalAIMessage("assistant", "Hello."),
                LocalAIMessage("user", "Name a colour."),
            ),
            request.messages,
        )
        assertEquals(0.7f, request.temperature)
        assertEquals(64, request.maxTokens)
    }

    @Test
    fun `reads a whole-number temperature and leaves absent options unset`() {
        val withTemperature = LocalAIRequest.from(bridgeParameters(
            """{"id":"op-2","instructions":"","messages":[{"role":"user","content":"Hi"}],"temperature":1}"""
        ))
        assertEquals(1f, withTemperature.temperature)
        assertNull(withTemperature.maxTokens)

        val bare = LocalAIRequest.from(bridgeParameters(
            """{"id":"op-3","instructions":"","messages":[{"role":"user","content":"Hi"}]}"""
        ))
        assertNull(bare.temperature)
    }

    @Test
    fun `rejects requests PHP validation would not send`() {
        listOf(
            """{"messages":[{"role":"user","content":"Hi"}]}""",
            """{"id":"op","messages":[]}""",
            """{"id":"op","messages":[{"role":"system","content":"Hi"}]}""",
            """{"id":"op","messages":[{"role":"user","content":""}]}""",
            """{"id":"op","messages":[{"role":"user","content":"Hi"},{"role":"assistant","content":"Yo"}]}""",
            """{"id":"op","messages":[{"role":"user","content":"Hi"}],"maxTokens":0}""",
        ).forEach { json ->
            val failure = assertFailsWith<LocalAIFailure>(json) { LocalAIRequest.from(bridgeParameters(json)) }
            assertEquals("invalid_request", failure.code)
        }
    }
}

package com.openminis.app.sandbox.offload

import com.openminis.app.sandbox.NativeOffloadRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxOffloadHandlerTest {
    private class FakeExecutor(
        var bridgeStatus: TermuxBridgeStatus = TermuxBridgeStatus(true, true),
        var result: TermuxCommandResult = TermuxCommandResult(0, "ok\n", ""),
    ) : TermuxCommandExecutor {
        var lastRequest: TermuxCommandRequest? = null
        override fun status(): TermuxBridgeStatus = bridgeStatus
        override fun execute(request: TermuxCommandRequest): TermuxCommandResult {
            lastRequest = request
            return result
        }
    }

    private fun request(vararg argv: String) = NativeOffloadRequest(
        pid = 1,
        argv = listOf("android-termux-cli") + argv,
        env = emptyMap(),
        cwd = "/",
        sessionId = "test",
    )

    @Test
    fun `status probes installed NetHunter`() {
        val executor = FakeExecutor()
        val result = TermuxOffloadHandler(executor) { true }.handle(request("status"))
        assertEquals(0, result.exitCode)
        assertEquals(TermuxContract.NETHUNTER, executor.lastRequest?.executable)
        assertEquals("-r", executor.lastRequest?.arguments?.first())
        assertTrue(JSONObject(result.output).getBoolean("ready"))
    }

    @Test
    fun `status reports missing run command permission without executing`() {
        val executor = FakeExecutor(TermuxBridgeStatus(true, false))
        val result = TermuxOffloadHandler(executor) { true }.handle(request("status"))
        assertEquals(0, result.exitCode)
        assertFalse(JSONObject(result.output).getBoolean("ready"))
        assertEquals(null, executor.lastRequest)
    }

    @Test
    fun `exec routes through Termux bash and clamps timeout`() {
        val executor = FakeExecutor()
        val result = TermuxOffloadHandler(executor) { true }.handle(
            request("exec", "--timeout-ms", "9999999", "printf", "hello"),
        )
        assertEquals(0, result.exitCode)
        assertEquals(TermuxContract.BASH, executor.lastRequest?.executable)
        assertEquals(listOf("-lc", "printf hello"), executor.lastRequest?.arguments)
        assertEquals(900_000L, executor.lastRequest?.timeoutMs)
    }

    @Test
    fun `nethunter routes command through rootless launcher`() {
        val executor = FakeExecutor()
        val result = TermuxOffloadHandler(executor) { true }.handle(
            request("nethunter", "cat", "/etc/os-release"),
        )
        assertEquals(0, result.exitCode)
        assertEquals(TermuxContract.NETHUNTER, executor.lastRequest?.executable)
        assertEquals(listOf("-r", "cat /etc/os-release"), executor.lastRequest?.arguments)
    }

    @Test
    fun `authorization denial prevents execution`() {
        val executor = FakeExecutor()
        val result = TermuxOffloadHandler(executor) { false }.handle(
            request("nethunter", "id"),
        )
        assertEquals(126, result.exitCode)
        assertEquals(null, executor.lastRequest)
        assertTrue(result.output.contains("permission_denied"))
    }

    @Test
    fun `command failure preserves structured stdout and stderr`() {
        val executor = FakeExecutor(
            result = TermuxCommandResult(7, "partial", "failed"),
        )
        val result = TermuxOffloadHandler(executor) { true }.handle(
            request("nethunter", "false"),
        )
        assertEquals(7, result.exitCode)
        val body = JSONObject(result.output)
        assertEquals("command_failed", body.getString("error"))
        assertEquals("partial", body.getJSONObject("data").getString("stdout"))
        assertEquals("failed", body.getJSONObject("data").getString("stderr"))
    }
}

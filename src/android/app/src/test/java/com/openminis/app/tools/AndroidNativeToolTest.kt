package com.openminis.app.tools

import com.openminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNativeToolTest {
    @Test
    fun `agent registry exposes one direct Android tool`() {
        val definitions = AgentTools.makeAgentTools(memoryEnabled = true)
        assertEquals(1, definitions.count { it.name == AndroidNativeTool.NAME })
        assertEquals(AndroidNativeTool.NAME, definitions.first().name)
    }

    @Test
    fun `OpenAI schema declares command enum and string array items`() {
        val function = AndroidNativeTool.definition().toOpenAIJson()
            .getJSONObject("function")
        val properties = function.getJSONObject("parameters")
            .getJSONObject("properties")
        val arguments = properties.getJSONObject("arguments")
        assertEquals("array", arguments.getString("type"))
        assertEquals("string", arguments.getJSONObject("items").getString("type"))
        val required = function.getJSONObject("parameters").getJSONArray("required")
        assertFalse((0 until required.length()).any { required.getString(it) == "arguments" })
        val commands = properties.getJSONObject("command").getJSONArray("enum")
        assertTrue((0 until commands.length()).any { commands.getString(it) == "android-wifi" })
        assertTrue((0 until commands.length()).any { commands.getString(it) == "android-root-cli" })
    }

    @Test
    fun `structured Wi-Fi invocation bypasses PRoot and preserves argv`() = runBlocking {
        var dispatchedName: String? = null
        var dispatchedArgv: List<String>? = null
        var dispatchedSession: String? = null
        val input = JSONObject()
            .put("tool_title", "Сканировать Wi-Fi")
            .put("command", "android-wifi")
            .put("arguments", JSONArray(listOf("scan", "--max", "100")))
            .toString()

        val result = AndroidNativeTool.execute(
            input,
            "session-42",
            AndroidNativeTool.Dispatcher { name, request ->
                dispatchedName = name
                dispatchedArgv = request.argv
                dispatchedSession = request.sessionId
                NativeOffloadResult(0, "{\"networks\":[]}")
            },
        )

        assertTrue(result.success)
        assertEquals("Сканировать Wi-Fi", result.toolTitle)
        assertEquals("android-wifi", dispatchedName)
        assertEquals(listOf("android-wifi", "scan", "--max", "100"), dispatchedArgv)
        assertEquals("session-42", dispatchedSession)
    }

    @Test
    fun `arguments may be omitted for no-argument Android command`() {
        val invocation = AndroidNativeTool.parseInvocation(
            JSONObject()
                .put("tool_title", "Device status")
                .put("command", "android-device")
                .toString(),
        )
        assertEquals(emptyList<String>(), invocation.arguments)
    }

    @Test
    fun `unambiguous user intent corrects wrong model route and unsafe argv`() {
        val location = AndroidNativeTool.parseInvocation(
            JSONObject()
                .put("tool_title", "android-speak")
                .put("command", "android-speak")
                .put("arguments", JSONArray(listOf("announce location")))
                .toString(),
            "Получи текущую геолокацию телефона.",
        )
        assertEquals("android-location", location.command)
        assertEquals(listOf("current"), location.arguments)

        val clipboard = AndroidNativeTool.parseInvocation(
            JSONObject()
                .put("tool_title", "Read clipboard")
                .put("command", "android-wifi")
                .toString(),
            "Прочитай буфер обмена телефона.",
        )
        assertEquals("android-clipboard", clipboard.command)
        assertEquals(listOf("get"), clipboard.arguments)

        val root = AndroidNativeTool.parseInvocation(
            JSONObject()
                .put("tool_title", "Check root")
                .put("command", "root-status")
                .put("arguments", JSONArray(listOf("whoami")))
                .toString(),
            "Проверь состояние root-доступа телефона.",
        )
        assertEquals("android-root-cli", root.command)
        assertEquals(listOf("status"), root.arguments)
    }

    @Test
    fun `ambiguous multi-capability intent is not silently rewritten`() {
        val invocation = AndroidNativeTool.parseInvocation(
            JSONObject()
                .put("tool_title", "Check radios")
                .put("command", "android-device")
                .put("arguments", JSONArray(listOf("all")))
                .toString(),
            "Просканируй Wi-Fi и Bluetooth вокруг телефона.",
        )
        assertEquals("android-device", invocation.command)
        assertEquals(listOf("all"), invocation.arguments)
    }

    @Test
    fun `all research surfaces are available through command enum`() {
        assertTrue(AndroidNativeTool.COMMANDS.containsAll(listOf(
            "android-wifi",
            "android-bluetooth",
            "android-hardware",
            "android-device",
            "android-location",
            "android-a11y-cli",
            "android-shizuku-cli",
            "android-root-cli",
        )))
        assertEquals(AndroidNativeTool.COMMANDS.size, AndroidNativeTool.COMMANDS.distinct().size)
    }

    @Test
    fun `unknown command is rejected before dispatch`() = runBlocking {
        var dispatched = false
        val input = JSONObject()
            .put("tool_title", "Bad")
            .put("command", "nmcli")
            .put("arguments", JSONArray())
            .toString()
        val result = AndroidNativeTool.execute(
            input,
            "session",
            AndroidNativeTool.Dispatcher { _, _ ->
                dispatched = true
                NativeOffloadResult(0, "unexpected")
            },
        )
        assertFalse(result.success)
        assertFalse(dispatched)
        assertTrue(result.output.contains("Unsupported Android command"))
    }

    @Test
    fun `simple android CLI is intercepted without shell`() = runBlocking {
        var argv: List<String>? = null
        val result = AndroidNativeTool.executeSimpleShellCommandIfSupported(
            "android-root-cli exec 'id && getenforce'",
            "session",
            "Check root",
            AndroidNativeTool.Dispatcher { _, request ->
                argv = request.argv
                NativeOffloadResult(0, "uid=0")
            },
        )
        assertNotNull(result)
        assertEquals(listOf("android-root-cli", "exec", "id && getenforce"), argv)
        assertTrue(result!!.success)
    }

    @Test
    fun `shell pipelines are not parsed as native CLI calls`() {
        assertNull(AndroidNativeTool.parseSimpleShellInvocation(
            "android-wifi status | jq .enabled",
            "status",
        ))
    }

    @Test
    fun `desktop nmcli Wi-Fi scan is safely redirected to Android`() = runBlocking {
        var argv: List<String>? = null
        val screenshotCommand = "nmcli -m tabular -f name,ssid,security,ip4.address " +
            "dev wifi list 2>/dev/null || iw dev 2>/dev/null"
        val result = AndroidNativeTool.executeSimpleShellCommandIfSupported(
            screenshotCommand,
            "session",
            "Scan Wi-Fi",
            AndroidNativeTool.Dispatcher { _, request ->
                argv = request.argv
                NativeOffloadResult(0, "{\"networks\":[]}")
            },
        )
        assertNotNull(result)
        assertTrue(result!!.success)
        assertEquals(listOf("android-wifi", "scan", "--max", "100"), argv)
    }

    @Test
    fun `Wi-Fi scan intent overrides package installation loop`() = runBlocking {
        var argv: List<String>? = null
        val result = AndroidNativeTool.executeSimpleShellCommandIfSupported(
            "apk add wps-nfc aircrack-ng-standalone python3",
            "session",
            "Install Wi-Fi hacking tools",
            AndroidNativeTool.Dispatcher { _, request ->
                argv = request.argv
                NativeOffloadResult(0, "{\"networks\":[]}")
            },
            userIntent = "Просканируй доступные Wi-Fi сети.",
        )
        assertNotNull(result)
        assertTrue(result!!.success)
        assertEquals("Scan Wi-Fi networks", result.toolTitle)
        assertEquals(listOf("android-wifi", "scan", "--max", "100"), argv)
    }

    @Test
    fun `desktop Bluetooth scan is safely redirected to Android`() {
        val invocation = AndroidNativeTool.parseDesktopRadioFallback(
            "timeout 12 bluetoothctl scan on",
            "Scan Bluetooth",
        )
        assertNotNull(invocation)
        assertEquals("android-bluetooth", invocation!!.command)
        assertEquals(listOf("scan", "--mode", "both", "--max", "100"), invocation.arguments)
    }

    @Test
    fun `unrelated desktop command is not redirected`() {
        assertNull(AndroidNativeTool.parseSimpleShellInvocation(
            "nmcli dev wifi list",
            "scan",
        ))
        assertNull(AndroidNativeTool.parseDesktopRadioFallback("lspci -nn", "hardware"))
    }

    @Test
    fun `shell infrastructure failure signatures are detected`() {
        assertTrue(AndroidNativeTool.isShellInfrastructureFailure(
            "[Shell not running]\n(exit code: -1)",
        ))
        assertTrue(AndroidNativeTool.isShellInfrastructureFailure(
            "[Shell unavailable: PRoot exited during startup (code 1)]",
        ))
        assertFalse(AndroidNativeTool.isShellInfrastructureFailure(
            "ERROR: unable to select packages: aircrack-ng",
        ))
    }

    @Test
    fun `sensitive argument is never added to synthetic result`() = runBlocking {
        val secret = "wifi-password-never-echo"
        val input = JSONObject()
            .put("tool_title", "Connect")
            .put("command", "android-wifi")
            .put("arguments", JSONArray(listOf(
                "connect", "--ssid", "Lab", "--security", "wpa2",
                "--password", secret,
            )))
            .toString()
        val result = AndroidNativeTool.execute(
            input,
            "session",
            AndroidNativeTool.Dispatcher { _, _ -> NativeOffloadResult(0, "connected") },
        )
        assertTrue(result.success)
        assertFalse(result.output.contains(secret))
    }
}

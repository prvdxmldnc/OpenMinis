package com.openminis.app.tools

import android.os.Process
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.NativeOffloadServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Direct structured access to Android-host capabilities without PRoot. */
object AndroidNativeTool {
    const val NAME = "android_native"

    val COMMANDS: List<String> = listOf(
        "android-alarm",
        "android-calendar",
        "android-clipboard",
        "android-contacts",
        "android-device",
        "android-location",
        "android-notification",
        "android-open",
        "android-photos",
        "android-player",
        "android-speak",
        "android-speech",
        "android-weather",
        "android-wifi",
        "android-bluetooth",
        "android-hardware",
        "android-root-cli",
        "android-shizuku-cli",
        "android-a11y-cli",
    )

    internal data class Invocation(
        val command: String,
        val arguments: List<String>,
        val toolTitle: String,
    )

    internal fun interface Dispatcher {
        fun dispatch(name: String, request: NativeOffloadRequest): NativeOffloadResult
    }

    private data class IntentRoute(
        val command: String,
        val defaultArguments: List<String>,
        val validSubcommands: Set<String>,
        val toolTitle: String,
    )

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description =
            "Use Android framework capabilities on the phone directly. Always use this " +
                "instead of shell_execute/nmcli/iw/bluetoothctl for Wi-Fi, Bluetooth, " +
                "device hardware, location, contacts, notifications, media, speech, " +
                "Accessibility, Shizuku, or an existing root backend. The command is one " +
                "of the android-* CLIs documented in the system prompt; arguments are the " +
                "same tokens that follow that CLI name. Example Wi-Fi scan: command=" +
                "android-wifi, arguments=[\"scan\",\"--max\",\"100\"].",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise user-facing description in the user's language.",
            ),
            "command" to AgentToolParam(
                "string",
                "Android-native command to invoke. Exact mappings: Wi-Fi=" +
                    "android-wifi; Bluetooth=android-bluetooth; GPS/location=" +
                    "android-location; clipboard=android-clipboard; device info=" +
                    "android-device; hardware/sensors=android-hardware; root=" +
                    "android-root-cli; Shizuku=android-shizuku-cli; Accessibility=" +
                    "android-a11y-cli.",
                enumValues = COMMANDS,
            ),
            "arguments" to AgentToolParam(
                "array",
                "Ordered command-line argument tokens, excluding the command name.",
                itemsType = "string",
            ),
        ),
        // Commands such as android-device/status need no argv. Keeping the array
        // optional also makes the schema friendlier to smaller local models.
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "arguments"),
    )

    internal fun parseInvocation(
        argsJson: String,
        userIntent: String? = null,
    ): Invocation {
        val input = JSONObject(argsJson)
        val rawCommand = input.optString("command", "")
        var command = normalizeCommandAlias(rawCommand)
        var toolTitle = input.optString("tool_title", NAME).ifBlank { NAME }
        val values = input.opt("arguments")
        val array = when (values) {
            null, JSONObject.NULL -> JSONArray()
            is JSONArray -> values
            else -> throw IllegalArgumentException("'arguments' must be an array of strings")
        }
        require(array.length() <= 64) { "Too many Android command arguments" }
        var arguments = ArrayList<String>(array.length())
        var totalChars = 0
        repeat(array.length()) { index ->
            val value = array.opt(index)
            require(value is String) { "Android command arguments must be strings" }
            require(value.length <= 8_192) { "Android command argument is too long" }
            totalChars += value.length
            require(totalChars <= 32_768) { "Android command arguments are too large" }
            arguments += value
        }

        // The local 9B model reliably emits a structured call but can select
        // the wrong enum member. Correct only unambiguous phone-capability
        // intents and only to known, read-only default operations. The user
        // text is inspected in memory and is never logged or returned.
        inferReadOnlyIntentRoute(userIntent)?.let { route ->
            val modelSubcommand = arguments.firstOrNull()
            if (command != route.command || modelSubcommand !in route.validSubcommands) {
                command = route.command
                arguments = ArrayList(route.defaultArguments)
            }
            // Do not keep misleading titles such as "Install Wi-Fi hacking
            // tools" when the request was deterministically routed to a
            // read-only Android scan.
            toolTitle = route.toolTitle
        }

        require(command in COMMANDS) { "Unsupported Android command: $rawCommand" }
        return Invocation(
            command = command,
            arguments = arguments,
            toolTitle = toolTitle,
        )
    }

    private fun normalizeCommandAlias(command: String): String = when (command.lowercase()) {
        "wifi", "android_wifi" -> "android-wifi"
        "bluetooth", "android_bluetooth" -> "android-bluetooth"
        "location", "gps", "android_location" -> "android-location"
        "clipboard", "android_clipboard" -> "android-clipboard"
        "device", "android_device" -> "android-device"
        "hardware", "android_hardware" -> "android-hardware"
        "root", "root-status", "android_root" -> "android-root-cli"
        "shizuku", "android_shizuku" -> "android-shizuku-cli"
        "accessibility", "a11y", "android_accessibility" -> "android-a11y-cli"
        else -> command
    }

    /** Return a route only when one capability and a read-only action are clear. */
    private fun inferReadOnlyIntentRoute(text: String?): IntentRoute? {
        if (text.isNullOrBlank()) return null
        val value = text.lowercase()
        val routes = buildList {
            if (containsAny(value, "wi-fi", "wifi", "вайфай", "вай-фай")) {
                when {
                    containsAny(value, "scan", "list network", "nearby network", "проскан", "доступн", "список сет") ->
                        add(IntentRoute("android-wifi", listOf("scan", "--max", "100"), WIFI_SUBCOMMANDS, "Scan Wi-Fi networks"))
                    containsAny(value, "status", "state", "состоя", "подключен") ->
                        add(IntentRoute("android-wifi", listOf("status"), WIFI_SUBCOMMANDS, "Check Wi-Fi status"))
                }
            }
            if (containsAny(value, "bluetooth", "блютуз", "блютус")) {
                when {
                    containsAny(value, "scan", "nearby", "around", "проскан", "рядом", "вокруг", "устройств") ->
                        add(IntentRoute("android-bluetooth", listOf("scan", "--mode", "both", "--max", "100"), BLUETOOTH_SUBCOMMANDS, "Scan Bluetooth devices"))
                    containsAny(value, "paired", "сопряж") ->
                        add(IntentRoute("android-bluetooth", listOf("paired"), BLUETOOTH_SUBCOMMANDS, "List paired Bluetooth devices"))
                    containsAny(value, "status", "state", "состоя") ->
                        add(IntentRoute("android-bluetooth", listOf("status"), BLUETOOTH_SUBCOMMANDS, "Check Bluetooth status"))
                }
            }
            if (containsAny(value, "location", "geolocation", "gps", "геолока", "местополож", "координат") &&
                containsAny(value, "current", "where am i", "текущ", "получи", "покажи", "узнай")) {
                add(IntentRoute("android-location", listOf("current"), LOCATION_SUBCOMMANDS, "Get current location"))
            }
            if (containsAny(value, "clipboard", "буфер обмена") &&
                containsAny(value, "read", "get", "show", "прочит", "покажи", "что в")) {
                add(IntentRoute("android-clipboard", listOf("get"), CLIPBOARD_SUBCOMMANDS, "Read Android clipboard"))
            }
            if (containsAny(value, "root", "рут") &&
                containsAny(value, "status", "state", "check", "состоя", "провер")) {
                add(IntentRoute("android-root-cli", listOf("status"), ROOT_SUBCOMMANDS, "Check Android root status"))
            }
            if (containsAny(value, "device info", "device details", "сведения об устрой", "информац об устрой")) {
                add(IntentRoute("android-device", listOf("all"), DEVICE_SUBCOMMANDS, "Read Android device information"))
            }
        }
        return routes.singleOrNull()
    }

    internal fun parseReadOnlyIntentInvocation(userIntent: String?): Invocation? =
        inferReadOnlyIntentRoute(userIntent)?.let { route ->
            Invocation(route.command, route.defaultArguments, route.toolTitle)
        }

    internal fun isShellInfrastructureFailure(output: String): Boolean =
        output.contains("[Shell not running]", ignoreCase = true) ||
            output.contains("[Shell unavailable:", ignoreCase = true) ||
            output.contains("PRoot binary not found", ignoreCase = true) ||
            output.contains("Rootfs installation failed", ignoreCase = true)

    private fun containsAny(value: String, vararg needles: String): Boolean =
        needles.any(value::contains)

    /** Parse a single android-* compatibility CLI without invoking a shell. */
    internal fun parseSimpleShellInvocation(
        commandLine: String,
        toolTitle: String,
    ): Invocation? {
        val tokens = tokenizeSimpleCommand(commandLine) ?: return null
        val command = tokens.firstOrNull() ?: return null
        if (command !in COMMANDS) return null
        return Invocation(command, tokens.drop(1), toolTitle)
    }

    /**
     * Recover a small, explicit set of desktop-Linux radio commands that local
     * models commonly emit for a phone. This is deliberately not a general
     * shell translator: only read-only discovery commands are redirected.
     */
    internal fun parseDesktopRadioFallback(
        commandLine: String,
        toolTitle: String,
    ): Invocation? {
        val normalized = commandLine.lowercase()
        return when {
            ("nmcli" in normalized &&
                ("wifi list" in normalized || "dev wifi" in normalized)) ||
                ("iwlist" in normalized && "scan" in normalized) ->
                Invocation("android-wifi", listOf("scan", "--max", "100"), toolTitle)

            "bluetoothctl" in normalized &&
                ("paired-devices" in normalized || "devices paired" in normalized) ->
                Invocation("android-bluetooth", listOf("paired"), toolTitle)

            "bluetoothctl" in normalized && "scan" in normalized ->
                Invocation(
                    "android-bluetooth",
                    listOf("scan", "--mode", "both", "--max", "100"),
                    toolTitle,
                )

            else -> null
        }
    }

    private fun tokenizeSimpleCommand(commandLine: String): List<String>? {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false

        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.setLength(0)
            }
        }

        for (char in commandLine.trim()) {
            if (escaping) {
                current.append(char)
                escaping = false
                continue
            }
            if (char == '\\' && quote != '\'') {
                escaping = true
                continue
            }
            if (quote != null) {
                if (char == quote) {
                    quote = null
                } else {
                    // Double-quoted $/backticks require real shell expansion;
                    // leave those commands on the normal PRoot path.
                    if (quote == '"' && (char == '$' || char == '`')) return null
                    current.append(char)
                }
                continue
            }
            when {
                char == '\'' || char == '"' -> quote = char
                char.isWhitespace() -> flush()
                char in "|&;<>()`$\n\r" -> return null
                else -> current.append(char)
            }
        }
        if (escaping || quote != null) return null
        flush()
        return tokens
    }

    internal suspend fun execute(
        argsJson: String,
        sessionId: String,
        dispatcher: Dispatcher = Dispatcher { name, request ->
            NativeOffloadServer.invokeRegistered(name, request)
        },
        userIntent: String? = null,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val invocation = parseInvocation(argsJson, userIntent)
            executeInvocation(invocation, sessionId, dispatcher)
        } catch (e: Exception) {
            ToolExecutionResult(
                output = "Error: ${e.message}",
                success = false,
                toolTitle = NAME,
            )
        }
    }

    internal suspend fun executeSimpleShellCommandIfSupported(
        commandLine: String,
        sessionId: String,
        toolTitle: String,
        dispatcher: Dispatcher = Dispatcher { name, request ->
            NativeOffloadServer.invokeRegistered(name, request)
        },
        userIntent: String? = null,
    ): ToolExecutionResult? {
        val invocation = parseSimpleShellInvocation(commandLine, toolTitle)
            ?: parseDesktopRadioFallback(commandLine, toolTitle)
            // Last-resort correction for a model that decides to install
            // aircrack/wps/bluez packages for a read-only phone scan. A clear
            // user intent is stronger evidence than the generated shell text.
            ?: parseReadOnlyIntentInvocation(userIntent)
            ?: return null
        return withContext(Dispatchers.IO) {
            executeInvocation(invocation, sessionId, dispatcher)
        }
    }

    private fun executeInvocation(
        invocation: Invocation,
        sessionId: String,
        dispatcher: Dispatcher,
    ): ToolExecutionResult {
        val request = NativeOffloadRequest(
            pid = Process.myPid(),
            argv = listOf(invocation.command) + invocation.arguments,
            env = emptyMap(),
            cwd = "/",
            sessionId = sessionId,
        )
        val result = dispatcher.dispatch(invocation.command, request)
        return ToolExecutionResult(
            output = result.output.ifBlank { "(no output)" },
            success = result.exitCode == 0,
            toolTitle = invocation.toolTitle,
        )
    }

    private val WIFI_SUBCOMMANDS = setOf("status", "scan", "enable", "disable", "saved", "connect", "forget")
    private val BLUETOOTH_SUBCOMMANDS = setOf("status", "paired", "scan", "pair", "enable", "disable")
    private val LOCATION_SUBCOMMANDS = setOf("current", "geocode", "forward")
    private val CLIPBOARD_SUBCOMMANDS = setOf("get", "set", "clear", "status")
    private val ROOT_SUBCOMMANDS = setOf("status", "exec")
    private val DEVICE_SUBCOMMANDS = setOf("all", "info", "battery", "storage")
}

package com.openminis.app.sandbox.offload

import android.content.Context
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject

/** Agent-facing bridge to the user's installed Termux and NetHunter Rootless. */
class TermuxOffloadHandler internal constructor(
    private val executor: TermuxCommandExecutor,
    private val authorize: (NativeOffloadRequest) -> Boolean,
) : NativeOffloadHandler {
    constructor(context: Context) : this(
        AndroidTermuxCommandExecutor(context.applicationContext),
        { request -> OffloadGate.allow("termux_cli", "android-termux-cli", request) },
    )

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val argv = request.argv.drop(1)
        val args = OffloadArgs(argv, setOf("compact", "quiet", "q"))
        if (argv.isEmpty() || argv.first() in setOf("help", "--help", "-h")) {
            return ok(HELP, args)
        }
        return when (argv.first().lowercase()) {
            "status" -> status(args)
            "exec", "termux" -> executeTermux(argv.drop(1), args, request)
            "nethunter", "kali", "nh" -> executeNetHunter(argv.drop(1), args, request)
            else -> error("invalid_args", "Unknown subcommand '${argv.first()}'", args, 2)
        }
    }

    private fun status(args: OffloadArgs): NativeOffloadResult {
        val bridge = executor.status()
        val data = JSONObject()
            .put("termux_installed", bridge.installed)
            .put("run_command_permission", bridge.runCommandPermissionGranted)
            .put("allow_external_apps_required", true)
        if (!bridge.installed || !bridge.runCommandPermissionGranted) {
            data.put("ready", false)
            return ok(data.toString(2), args)
        }
        val probe = executor.execute(
            TermuxCommandRequest(
                executable = TermuxContract.NETHUNTER,
                arguments = listOf("-r", "printf 'nethunter=ok\\n'; sed -n 's/^PRETTY_NAME=//p' /etc/os-release"),
                timeoutMs = 15_000L,
            ),
        )
        data.put("ready", probe.exitCode == 0)
            .put("nethunter_exit_code", probe.exitCode)
            .put("nethunter", probe.stdout.trim())
        if (probe.stderr.isNotBlank()) data.put("stderr", probe.stderr.take(MAX_OUTPUT_CHARS))
        return ok(data.toString(2), args)
    }

    private fun executeTermux(
        raw: List<String>,
        args: OffloadArgs,
        request: NativeOffloadRequest,
    ): NativeOffloadResult {
        if (!authorize(request)) return permissionDenied(args)
        val command = stripBridgeOptions(raw).joinToString(" ").trim()
        if (command.isEmpty()) return error("invalid_args", "exec requires a shell command", args, 2)
        return execute(
            backend = "termux",
            commandRequest = TermuxCommandRequest(
                executable = TermuxContract.BASH,
                arguments = listOf("-lc", command),
                timeoutMs = timeout(args),
            ),
            args = args,
        )
    }

    private fun executeNetHunter(
        raw: List<String>,
        args: OffloadArgs,
        request: NativeOffloadRequest,
    ): NativeOffloadResult {
        if (!authorize(request)) return permissionDenied(args)
        val command = stripBridgeOptions(raw).joinToString(" ").trim()
        if (command.isEmpty()) return error("invalid_args", "nethunter requires a Kali command", args, 2)
        return execute(
            backend = "nethunter-rootless",
            commandRequest = TermuxCommandRequest(
                executable = TermuxContract.NETHUNTER,
                arguments = listOf("-r", command),
                timeoutMs = timeout(args),
            ),
            args = args,
        )
    }

    private fun execute(
        backend: String,
        commandRequest: TermuxCommandRequest,
        args: OffloadArgs,
    ): NativeOffloadResult {
        val result = executor.execute(commandRequest)
        val data = JSONObject()
            .put("backend", backend)
            .put("exit_code", result.exitCode)
            .put("stdout", result.stdout.take(MAX_OUTPUT_CHARS))
            .put("stderr", result.stderr.take(MAX_OUTPUT_CHARS))
            .put("output_truncated", result.outputWasTruncated ||
                result.stdout.length > MAX_OUTPUT_CHARS || result.stderr.length > MAX_OUTPUT_CHARS)
        if (result.internalErrorCode != -1) {
            data.put("termux_error_code", result.internalErrorCode)
            data.put("termux_error", result.internalErrorMessage.take(4_000))
        }
        return if (result.exitCode == 0 && result.internalErrorCode == -1) {
            ok(data.toString(2), args)
        } else {
            errorWithData(
                code = when (result.exitCode) {
                    124 -> "timeout"
                    126 -> "permission_or_setup_required"
                    127 -> "termux_unavailable"
                    else -> "command_failed"
                },
                message = (result.internalErrorMessage.ifBlank { result.stderr })
                    .ifBlank { "Termux command failed" }
                    .take(4_000),
                data = data,
                args = args,
                exitCode = result.exitCode.coerceIn(1, 255),
            )
        }
    }

    private fun timeout(args: OffloadArgs): Long =
        (args.getLong("timeout-ms") ?: DEFAULT_TIMEOUT_MS).coerceIn(100L, MAX_TIMEOUT_MS)

    private fun stripBridgeOptions(raw: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        var options = true
        while (i < raw.size) {
            val value = raw[i]
            when {
                options && value == "--" -> options = false
                options && value.startsWith("--timeout-ms=") -> Unit
                options && value == "--timeout-ms" -> i++
                options && value in setOf("--compact", "--quiet", "-q") -> Unit
                else -> out += value
            }
            i++
        }
        return out
    }

    private fun permissionDenied(args: OffloadArgs): NativeOffloadResult = error(
        "permission_denied",
        "Agent is not allowed to use android-termux-cli. Open Settings -> Permissions to change.",
        args,
        126,
    )

    private fun ok(body: String, args: OffloadArgs) =
        NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")

    private fun error(code: String, message: String, args: OffloadArgs, exitCode: Int = 1) =
        errorWithData(code, message, null, args, exitCode)

    private fun errorWithData(
        code: String,
        message: String,
        data: JSONObject?,
        args: OffloadArgs,
        exitCode: Int,
    ): NativeOffloadResult {
        val body = JSONObject().put("error", code).put("message", message)
        if (data != null) body.put("data", data)
        return NativeOffloadResult(exitCode, OffloadOutput.formatBody(body.toString(), args) + "\n")
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 120_000L
        private const val MAX_TIMEOUT_MS = 900_000L
        private const val MAX_OUTPUT_CHARS = 100_000

        private const val HELP = """android-termux-cli - Termux and Kali NetHunter Rootless bridge

Usage:
  android-termux-cli status
  android-termux-cli exec [--timeout-ms 120000] <Termux shell command...>
  android-termux-cli nethunter [--timeout-ms 120000] <Kali command...>

Aliases: termux for exec; kali/nh for nethunter.
Commands execute through Termux's official RUN_COMMAND service. Minis needs the
com.termux.permission.RUN_COMMAND grant and Termux must contain
allow-external-apps=true in ~/.termux/termux.properties.
"""
    }
}

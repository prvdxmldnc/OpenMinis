package com.openminis.app.sandbox.offload

import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Explicit unrestricted root shell for research builds.
 *
 * This does not exploit or root a phone. It uses a Shizuku-compatible service
 * already running as uid 0, or an already-installed `su` implementation. The
 * command itself is deliberately not allow-listed or rewritten.
 */
class RootOffloadHandler : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val argv = request.argv.drop(1)
        val args = OffloadArgs(argv, setOf("compact", "quiet", "q"))
        if (argv.isEmpty() || argv.first() in setOf("help", "--help", "-h")) return ok(HELP, args)
        OffloadGate.enforce("root_cli", "android-root-cli", args, request)?.let { return it }
        return when (argv.first()) {
            "status" -> status(args)
            "exec" -> execute(argv.drop(1), args)
            else -> error("invalid_args", "Unknown subcommand '${argv.first()}'", args, 2)
        }
    }

    private fun status(args: OffloadArgs): NativeOffloadResult {
        ShizukuManager.refresh()
        val snap = ShizukuManager.snapshot.value
        val shizukuRoot = snap.state == ShizukuManager.State.READY && snap.uid == 0
        val su = if (shizukuRoot) null else runSu("id", 5_000L)
        val data = JSONObject()
            .put("available", shizukuRoot || su?.exitCode == 0)
            .put("preferred_backend", when {
                shizukuRoot -> "shizuku_uid_0"
                su?.exitCode == 0 -> "su"
                else -> JSONObject.NULL
            })
            .put("shizuku_state", snap.state.name)
            .put("shizuku_uid", snap.uid)
            .put("su_available", su?.exitCode == 0)
            .put("identity", (if (shizukuRoot) "uid=0(root)" else su?.stdout?.trim()).orEmpty())
        return ok(data.toString(2), args)
    }

    private fun execute(raw: List<String>, args: OffloadArgs): NativeOffloadResult {
        val commandTokens = ResearchToolCommands.stripRootOptions(raw)
        if (commandTokens.isEmpty()) return error("invalid_args", "exec requires a shell command", args, 2)
        val command = commandTokens.joinToString(" ")
        val timeoutMs = (args.getLong("timeout-ms") ?: 60_000L).coerceIn(100L, 900_000L)
        val requestedBackend = (args.get("backend") ?: "auto").lowercase()
        if (requestedBackend !in setOf("auto", "shizuku", "su")) {
            return error("invalid_args", "--backend must be auto, shizuku, or su", args, 2)
        }
        ShizukuManager.refresh()
        val shizukuRoot = ShizukuManager.isReady() && ShizukuManager.snapshot.value.uid == 0
        val backend = when {
            requestedBackend == "shizuku" && shizukuRoot -> "shizuku"
            requestedBackend == "shizuku" -> return error(
                "root_unavailable",
                "Shizuku/AXManager is not READY as uid 0",
                args,
                126,
            )
            requestedBackend == "su" -> "su"
            shizukuRoot -> "shizuku"
            else -> "su"
        }
        val result = if (backend == "shizuku") {
            ShizukuManager.runProcess(arrayOf("sh", "-c", command), timeoutMs = timeoutMs)
        } else {
            runSu(command, timeoutMs)
        }
        val data = JSONObject()
            .put("backend", backend)
            .put("exit_code", result.exitCode)
            .put("stdout", truncate(result.stdout))
            .put("stderr", truncate(result.stderr))
            .put("output_truncated", result.stdout.length > MAX_OUTPUT_CHARS || result.stderr.length > MAX_OUTPUT_CHARS)
        return if (result.exitCode == 0) ok(data.toString(2), args)
        else errorWithData(
            if (result.exitCode == 124) "timeout" else "command_failed",
            result.combined.ifBlank { "Root command failed" }.take(4_000),
            data,
            args,
            result.exitCode.coerceIn(1, 255),
        )
    }

    private fun runSu(command: String, timeoutMs: Long): ShizukuManager.ProcessResult {
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            val pool = Executors.newFixedThreadPool(2)
            try {
                val stdoutFuture = pool.submit<String> { readLimited(process.inputStream) }
                val stderrFuture = pool.submit<String> { readLimited(process.errorStream) }
                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) process.destroyForcibly()
                val stdout = runCatching { stdoutFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("")
                val stderr = runCatching { stderrFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("")
                ShizukuManager.ProcessResult(if (finished) process.exitValue() else 124, stdout, stderr)
            } finally {
                pool.shutdownNow()
            }
        } catch (t: Throwable) {
            ShizukuManager.ProcessResult(126, "", "su unavailable: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun readLimited(input: InputStream): String {
        val buffer = ByteArray(8_192)
        val out = StringBuilder()
        var truncated = false
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                val remaining = MAX_OUTPUT_CHARS - out.length
                if (remaining > 0) {
                    val text = String(buffer, 0, read, Charsets.UTF_8)
                    out.append(text.take(remaining))
                    if (text.length > remaining) truncated = true
                } else {
                    // Keep draining the pipe so a verbose command cannot block
                    // or receive SIGPIPE merely because its response is capped.
                    truncated = true
                }
            }
        }
        return out.toString() + if (truncated) "\n[output truncated]" else ""
    }

    private fun truncate(value: String): String = if (value.length <= MAX_OUTPUT_CHARS) value
    else value.take(MAX_OUTPUT_CHARS) + "\n[output truncated]"

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
        private const val MAX_OUTPUT_CHARS = 1_048_576

        private const val HELP = """android-root-cli - unrestricted root shell for research devices

Usage:
  android-root-cli status
  android-root-cli exec [--backend auto|shizuku|su] [--timeout-ms 60000] <command...>

The command is passed unchanged to `sh -c` under an existing uid-0 Shizuku/
AXManager service, or to an existing `su -c`. No command allow-list is applied.
This tool does not root a device or exploit Android; root must already exist and
the installed root manager may display its own grant dialog.
"""
    }
}

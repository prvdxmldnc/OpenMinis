package com.openminis.app.sandbox.offload

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

internal data class TermuxBridgeStatus(
    val installed: Boolean,
    val runCommandPermissionGranted: Boolean,
)

internal data class TermuxCommandRequest(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String = TermuxContract.HOME,
    val timeoutMs: Long,
)

internal data class TermuxCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val internalErrorCode: Int = -1,
    val internalErrorMessage: String = "",
    val stdoutOriginalLength: Int = stdout.length,
    val stderrOriginalLength: Int = stderr.length,
) {
    val outputWasTruncated: Boolean
        get() = stdoutOriginalLength > stdout.length || stderrOriginalLength > stderr.length
}

internal interface TermuxCommandExecutor {
    fun status(): TermuxBridgeStatus
    fun execute(request: TermuxCommandRequest): TermuxCommandResult
}

/** Constants from Termux v0.118.3's public RUN_COMMAND contract. */
internal object TermuxContract {
    const val PACKAGE = "com.termux"
    const val SERVICE = "com.termux.app.RunCommandService"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val ACTION = "com.termux.RUN_COMMAND"
    const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    const val RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDERR = "stderr"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERR = "err"
    const val RESULT_ERRMSG = "errmsg"
    const val RESULT_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
    const val RESULT_STDERR_ORIGINAL_LENGTH = "stderr_original_length"
    const val PREFIX = "/data/data/com.termux/files/usr"
    const val HOME = "/data/data/com.termux/files/home"
    const val BASH = "$PREFIX/bin/bash"
    const val NETHUNTER = "$PREFIX/bin/nethunter"
    const val CALLBACK_EXECUTION_ID = "com.openminis.app.TERMUX_EXECUTION_ID"
}

/** Executes a command in the installed Termux app and synchronously awaits its result. */
internal class AndroidTermuxCommandExecutor(private val context: Context) : TermuxCommandExecutor {
    override fun status(): TermuxBridgeStatus = TermuxBridgeStatus(
        installed = isPackageInstalled(),
        runCommandPermissionGranted = ContextCompat.checkSelfPermission(
            context,
            TermuxContract.PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED,
    )

    override fun execute(request: TermuxCommandRequest): TermuxCommandResult {
        val status = status()
        if (!status.installed) {
            return TermuxCommandResult(127, "", "Termux is not installed")
        }
        if (!status.runCommandPermissionGranted) {
            return TermuxCommandResult(
                126,
                "",
                "Minis does not have com.termux.permission.RUN_COMMAND",
            )
        }

        val registration = TermuxResultRegistry.register()
        val callback = Intent(context, TermuxResultReceiver::class.java)
            .putExtra(TermuxContract.CALLBACK_EXECUTION_ID, registration.id)
        val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(context, registration.id, callback, flags)

        val commandIntent = Intent(TermuxContract.ACTION).apply {
            setClassName(TermuxContract.PACKAGE, TermuxContract.SERVICE)
            putExtra(TermuxContract.EXTRA_PATH, request.executable)
            putExtra(TermuxContract.EXTRA_ARGUMENTS, request.arguments.toTypedArray())
            putExtra(TermuxContract.EXTRA_WORKDIR, request.workingDirectory)
            putExtra(TermuxContract.EXTRA_BACKGROUND, true)
            putExtra(TermuxContract.EXTRA_PENDING_INTENT, pendingIntent)
        }

        return try {
            val component = context.startService(commandIntent)
            if (component == null) {
                TermuxResultRegistry.cancel(registration.id)
                TermuxCommandResult(127, "", "Termux RunCommandService was not resolved")
            } else {
                registration.future.get(request.timeoutMs, TimeUnit.MILLISECONDS)
            }
        } catch (_: TimeoutException) {
            TermuxResultRegistry.cancel(registration.id)
            TermuxCommandResult(124, "", "Termux command timed out after ${request.timeoutMs} ms")
        } catch (t: Throwable) {
            TermuxResultRegistry.cancel(registration.id)
            TermuxCommandResult(
                126,
                "",
                "Unable to start Termux command: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                TermuxContract.PACKAGE,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            context.packageManager.getPackageInfo(TermuxContract.PACKAGE, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

internal object TermuxResultRegistry {
    data class Registration(
        val id: Int,
        val future: CompletableFuture<TermuxCommandResult>,
    )

    private val nextId = AtomicInteger(20_000)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<TermuxCommandResult>>()

    fun register(): Registration {
        val id = nextId.updateAndGet { current -> if (current == Int.MAX_VALUE) 20_000 else current + 1 }
        val future = CompletableFuture<TermuxCommandResult>()
        pending[id] = future
        return Registration(id, future)
    }

    fun cancel(id: Int) {
        pending.remove(id)?.cancel(false)
    }

    fun complete(id: Int, result: TermuxCommandResult) {
        pending.remove(id)?.complete(result)
    }
}

/** Explicit callback target carried by a one-shot PendingIntent. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(TermuxContract.CALLBACK_EXECUTION_ID, -1)
        if (id < 0) return
        val bundle = intent.getBundleExtra(TermuxContract.RESULT_BUNDLE)
        if (bundle == null) {
            TermuxResultRegistry.complete(
                id,
                TermuxCommandResult(1, "", "Termux returned no result bundle"),
            )
            return
        }
        val stdout = bundle.getString(TermuxContract.RESULT_STDOUT).orEmpty()
        val stderr = bundle.getString(TermuxContract.RESULT_STDERR).orEmpty()
        TermuxResultRegistry.complete(
            id,
            TermuxCommandResult(
                exitCode = bundle.getInt(TermuxContract.RESULT_EXIT_CODE, 1),
                stdout = stdout,
                stderr = stderr,
                internalErrorCode = bundle.getInt(TermuxContract.RESULT_ERR, -1),
                internalErrorMessage = bundle.getString(TermuxContract.RESULT_ERRMSG).orEmpty(),
                stdoutOriginalLength = bundle.getString(
                    TermuxContract.RESULT_STDOUT_ORIGINAL_LENGTH,
                )?.toIntOrNull() ?: stdout.length,
                stderrOriginalLength = bundle.getString(
                    TermuxContract.RESULT_STDERR_ORIGINAL_LENGTH,
                )?.toIntOrNull() ?: stderr.length,
            ),
        )
    }
}

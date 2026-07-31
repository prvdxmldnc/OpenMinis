package com.openminis.app.sandbox.offload

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native Wi-Fi discovery plus privileged configuration via Shizuku/root. */
class WifiOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val argv = request.argv.drop(1)
        val args = OffloadArgs(argv, setOf("compact", "quiet", "q"))
        if (argv.isEmpty() || argv.first() in setOf("help", "--help", "-h")) return ok(HELP, args)
        OffloadGate.enforce("wifi_cli", "android-wifi", args, request)?.let { return it }

        return try {
            when (val sub = args.positional.firstOrNull()) {
                "status" -> status(args)
                "scan" -> scan(args)
                "enable" -> privileged(arrayOf("svc", "wifi", "enable"), "enabled", args)
                "disable" -> privileged(arrayOf("svc", "wifi", "disable"), "disabled", args)
                "saved" -> privilegedRaw(arrayOf("cmd", "wifi", "list-networks"), args)
                "connect" -> connect(args)
                "forget" -> forget(args)
                else -> error("invalid_args", "Unknown subcommand '$sub'", args, 2)
            }
        } catch (t: Throwable) {
            error("operation_failed", t.message ?: t.javaClass.simpleName, args)
        }
    }

    private fun status(args: OffloadArgs): NativeOffloadResult {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return error("unsupported", "Wi-Fi service is unavailable", args)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = cm?.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            caps?.transportInfo as? WifiInfo
        } else {
            @Suppress("DEPRECATION")
            wifi.connectionInfo
        }
        val data = JSONObject()
            .put("enabled", wifi.isWifiEnabled)
            .put("connected", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
            .put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("ssid", cleanSsid(info?.ssid))
            .put("bssid", info?.bssid ?: JSONObject.NULL)
            .put("rssi_dbm", info?.rssi ?: JSONObject.NULL)
            .put("link_speed_mbps", info?.linkSpeed ?: JSONObject.NULL)
            .put("frequency_mhz", if (Build.VERSION.SDK_INT >= 21) info?.frequency ?: JSONObject.NULL else JSONObject.NULL)
            .put("fine_location_granted", hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            .put("shizuku_state", ShizukuManager.snapshot.value.state.name)
            .put("shizuku_uid", ShizukuManager.snapshot.value.uid)
        return ok(data.toString(2), args)
    }

    @Suppress("DEPRECATION")
    private fun scan(args: OffloadArgs): NativeOffloadResult {
        if (!ensurePermission(listOf(Manifest.permission.ACCESS_FINE_LOCATION))) {
            return error(
                "permission_denied",
                "Wi-Fi scanning requires Android location permission and Location services enabled.",
                args,
                126,
            )
        }
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return error("unsupported", "Wi-Fi service is unavailable", args)
        val timeoutSeconds = (args.getInt("timeout") ?: 12).coerceIn(1, 30)
        val max = (args.getInt("max") ?: 100).coerceIn(1, 500)
        val latch = CountDownLatch(1)
        var resultsUpdated = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                resultsUpdated = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) == true
                latch.countDown()
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        val started = try {
            wifi.startScan().also { if (it) latch.await(timeoutSeconds.toLong(), TimeUnit.SECONDS) }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }

        val rows = wifi.scanResults
            .sortedByDescending { it.level }
            .take(max)
        val data = JSONObject()
            .put("fresh_scan_started", started)
            .put("results_updated", resultsUpdated)
            .put("throttled_or_cached", !started || !resultsUpdated)
            .put("count", rows.size)
            .put("networks", JSONArray().apply { rows.forEach { put(scanResultJson(it)) } })
        return ok(data.toString(2), args)
    }

    private fun connect(args: OffloadArgs): NativeOffloadResult {
        val ssid = args.get("ssid") ?: return error("invalid_args", "connect requires --ssid", args, 2)
        val security = (args.get("security") ?: "wpa2").lowercase()
        if (security !in ResearchToolCommands.wifiSecurityModes) {
            return error("invalid_args", "--security must be one of ${ResearchToolCommands.wifiSecurityModes.joinToString()}", args, 2)
        }
        val password = args.get("password")
        if (security !in setOf("open", "owe") && password.isNullOrEmpty()) {
            return error("invalid_args", "$security requires --password", args, 2)
        }
        val cmd = ResearchToolCommands.wifiConnect(ssid, security, password)
        val result = runPrivileged(cmd.toTypedArray(), 30_000L)
        val data = JSONObject()
            .put("requested", result.exitCode == 0)
            .put("ssid", ssid)
            .put("security", security)
            .put("exit_code", result.exitCode)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
        return if (result.exitCode == 0) ok(data.toString(2), args)
        else error("operation_failed", result.combined.ifBlank { "Wi-Fi connect failed" }, args)
    }

    private fun forget(args: OffloadArgs): NativeOffloadResult {
        val id = args.get("network-id") ?: args.positional.getOrNull(1)
            ?: return error("invalid_args", "forget requires --network-id <id>", args, 2)
        return privileged(arrayOf("cmd", "wifi", "forget-network", id), "forget_requested", args)
    }

    private fun privileged(command: Array<String>, action: String, args: OffloadArgs): NativeOffloadResult {
        val result = runPrivileged(command)
        val data = JSONObject()
            .put("action", action)
            .put("exit_code", result.exitCode)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
        return if (result.exitCode == 0) ok(data.toString(2), args)
        else error("operation_failed", result.combined.ifBlank { "$action failed" }, args)
    }

    private fun privilegedRaw(command: Array<String>, args: OffloadArgs): NativeOffloadResult {
        val result = runPrivileged(command)
        val data = JSONObject()
            .put("exit_code", result.exitCode)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
        return if (result.exitCode == 0) ok(data.toString(2), args)
        else error("operation_failed", result.combined.ifBlank { "Privileged command failed" }, args)
    }

    private fun runPrivileged(command: Array<String>, timeoutMs: Long = 10_000L): ShizukuManager.ProcessResult {
        ShizukuManager.refresh()
        if (!ShizukuManager.isReady()) {
            return ShizukuManager.ProcessResult(
                126,
                "",
                "Shizuku/AXManager is not ready. Install, start, and authorize it in Settings > Permissions.",
            )
        }
        return ShizukuManager.runProcess(command, timeoutMs = timeoutMs)
    }

    private fun ensurePermission(permissions: List<String>): Boolean {
        val missing = permissions.filterNot(::hasPermission)
        if (missing.isEmpty()) return true
        val result = runBlocking { OffloadPermissionManager.requestAndroidPermission(missing) }
        return result == OffloadPermissionManager.AndroidPermissionResult.GRANTED && missing.all(::hasPermission)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun scanResultJson(result: ScanResult): JSONObject = JSONObject()
        .put("ssid", if (Build.VERSION.SDK_INT >= 33) result.wifiSsid?.toString() ?: result.SSID else result.SSID)
        .put("bssid", result.BSSID)
        .put("rssi_dbm", result.level)
        .put("frequency_mhz", result.frequency)
        .put("channel_width", if (Build.VERSION.SDK_INT >= 23) result.channelWidth else JSONObject.NULL)
        .put("capabilities", result.capabilities)
        .put("timestamp_us", result.timestamp)

    private fun ok(body: String, args: OffloadArgs) =
        NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")

    private fun error(code: String, message: String, args: OffloadArgs, exitCode: Int = 1): NativeOffloadResult {
        val body = JSONObject().put("error", code).put("message", message).toString()
        return NativeOffloadResult(exitCode, OffloadOutput.formatBody(body, args) + "\n")
    }

    companion object {
        internal fun cleanSsid(value: String?): Any =
            value?.removeSurrounding("\"")?.takeUnless { it == "<unknown ssid>" } ?: JSONObject.NULL

        private const val HELP = """android-wifi - native Wi-Fi discovery and privileged control

Usage:
  android-wifi status
  android-wifi scan [--timeout 12] [--max 100]
  android-wifi enable | disable
  android-wifi saved
  android-wifi connect --ssid NAME --security open|owe|wpa2|wpa3 [--password VALUE]
  android-wifi forget --network-id ID

Scanning uses Android's WifiManager and may return cached results when the OS
throttles scans. Configuration commands require authorized Shizuku/AXManager;
their effective privilege is adb-shell (uid 2000) or root (uid 0), depending
on how the service was started. Password values are never copied into output.
"""
    }
}

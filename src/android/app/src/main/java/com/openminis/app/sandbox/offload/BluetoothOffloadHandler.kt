package com.openminis.app.sandbox.offload

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Classic Bluetooth and BLE discovery with explicit Shizuku control hooks. */
class BluetoothOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val argv = request.argv.drop(1)
        val args = OffloadArgs(argv, setOf("compact", "quiet", "q"))
        if (argv.isEmpty() || argv.first() in setOf("help", "--help", "-h")) return ok(HELP, args)
        OffloadGate.enforce("bluetooth_cli", "android-bluetooth", args, request)?.let { return it }

        return try {
            when (val sub = args.positional.firstOrNull()) {
                "status" -> status(args)
                "paired" -> paired(args)
                "scan" -> scan(args)
                "pair" -> pair(args)
                "enable" -> privilegedToggle(true, args)
                "disable" -> privilegedToggle(false, args)
                else -> error("invalid_args", "Unknown subcommand '$sub'", args, 2)
            }
        } catch (t: Throwable) {
            error("operation_failed", t.message ?: t.javaClass.simpleName, args)
        }
    }

    private fun adapter(): BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun status(args: OffloadArgs): NativeOffloadResult {
        val adapter = adapter()
        val snap = ShizukuManager.snapshot.value
        val data = JSONObject()
            .put("supported", adapter != null)
            .put("enabled", adapter?.isEnabled == true)
            .put("discovering", runCatching { adapter?.isDiscovering == true }.getOrDefault(false))
            .put("scan_permission", scanPermissions().all(::hasPermission))
            .put("connect_permission", connectPermissions().all(::hasPermission))
            .put("shizuku_state", snap.state.name)
            .put("shizuku_uid", snap.uid)
        return ok(data.toString(2), args)
    }

    private fun paired(args: OffloadArgs): NativeOffloadResult {
        if (!ensurePermissions(connectPermissions())) {
            return error("permission_denied", "Bluetooth connect permission was not granted", args, 126)
        }
        val adapter = adapter() ?: return error("unsupported", "Bluetooth is unavailable", args)
        val devices = adapter.bondedDevices.orEmpty().sortedBy { safeName(it) ?: it.address }
        val data = JSONObject()
            .put("count", devices.size)
            .put("devices", JSONArray().apply { devices.forEach { put(deviceJson(it, null, "classic")) } })
        return ok(data.toString(2), args)
    }

    private fun scan(args: OffloadArgs): NativeOffloadResult {
        if (!ensurePermissions((scanPermissions() + connectPermissions()).distinct())) {
            return error("permission_denied", "Bluetooth scan/connect permission was not granted", args, 126)
        }
        val adapter = adapter() ?: return error("unsupported", "Bluetooth is unavailable", args)
        if (!adapter.isEnabled) return error("bluetooth_disabled", "Bluetooth is disabled", args)
        val mode = (args.get("mode") ?: "both").lowercase()
        if (mode !in setOf("classic", "ble", "both")) {
            return error("invalid_args", "--mode must be classic, ble, or both", args, 2)
        }
        val totalSeconds = (args.getInt("timeout") ?: 16).coerceIn(2, 30)
        val max = (args.getInt("max") ?: 100).coerceIn(1, 500)
        val perMode = if (mode == "both") (totalSeconds / 2).coerceAtLeast(1) else totalSeconds
        val devices = Collections.synchronizedMap(linkedMapOf<String, JSONObject>())
        if (mode == "classic" || mode == "both") scanClassic(adapter, perMode, devices)
        if (mode == "ble" || mode == "both") scanBle(adapter, perMode, devices)
        val rows = synchronized(devices) {
            devices.values.sortedByDescending { it.optInt("rssi_dbm", Int.MIN_VALUE) }.take(max)
        }
        val data = JSONObject()
            .put("mode", mode)
            .put("count", rows.size)
            .put("devices", JSONArray().apply { rows.forEach(::put) })
        return ok(data.toString(2), args)
    }

    private fun scanClassic(
        adapter: BluetoothAdapter,
        timeoutSeconds: Int,
        devices: MutableMap<String, JSONObject>,
    ) {
        val finished = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        device?.let { devices[it.address] = deviceJson(it, rssi, "classic") }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> finished.countDown()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            if (adapter.startDiscovery()) finished.await(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } finally {
            runCatching { adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun scanBle(
        adapter: BluetoothAdapter,
        timeoutSeconds: Int,
        devices: MutableMap<String, JSONObject>,
    ) {
        val scanner = adapter.bluetoothLeScanner ?: return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val json = deviceJson(result.device, result.rssi, "ble")
                result.scanRecord?.let { record ->
                    json.put("connectable", if (Build.VERSION.SDK_INT >= 26) result.isConnectable else JSONObject.NULL)
                    json.put("tx_power", record.txPowerLevel)
                    json.put("service_uuids", JSONArray().apply {
                        record.serviceUuids.orEmpty().forEach { put(it.uuid.toString()) }
                    })
                }
                devices[result.device.address] = json
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, callback)
        try {
            Thread.sleep(TimeUnit.SECONDS.toMillis(timeoutSeconds.toLong()))
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    private fun pair(args: OffloadArgs): NativeOffloadResult {
        if (!ensurePermissions(connectPermissions())) {
            return error("permission_denied", "Bluetooth connect permission was not granted", args, 126)
        }
        val address = args.get("address") ?: args.positional.getOrNull(1)
            ?: return error("invalid_args", "pair requires --address XX:XX:XX:XX:XX:XX", args, 2)
        if (!ResearchToolCommands.isBluetoothAddress(address)) {
            return error("invalid_args", "Invalid Bluetooth MAC address", args, 2)
        }
        val device = adapter()?.getRemoteDevice(address)
            ?: return error("unsupported", "Bluetooth is unavailable", args)
        val requested = device.createBond()
        val data = JSONObject()
            .put("pairing_requested", requested)
            .put("address", address.uppercase())
            .put("note", "Android may show a system pairing confirmation dialog")
        return if (requested) ok(data.toString(2), args)
        else error("operation_failed", "Android rejected the pairing request", args)
    }

    private fun privilegedToggle(enable: Boolean, args: OffloadArgs): NativeOffloadResult {
        ShizukuManager.refresh()
        if (!ShizukuManager.isReady()) {
            return error(
                "privileged_backend_required",
                "Authorized Shizuku/AXManager is required to change Bluetooth state",
                args,
                126,
            )
        }
        val result = ShizukuManager.runProcess(
            arrayOf("svc", "bluetooth", if (enable) "enable" else "disable"),
            timeoutMs = 10_000L,
        )
        val data = JSONObject()
            .put("requested_state", if (enable) "enabled" else "disabled")
            .put("exit_code", result.exitCode)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
        return if (result.exitCode == 0) ok(data.toString(2), args)
        else error("operation_failed", result.combined.ifBlank { "Bluetooth state change failed" }, args)
    }

    private fun deviceJson(device: BluetoothDevice, rssi: Int?, transport: String): JSONObject = JSONObject()
        .put("name", safeName(device) ?: JSONObject.NULL)
        .put("address", device.address)
        .put("transport", transport)
        .put("rssi_dbm", rssi ?: JSONObject.NULL)
        .put("bond_state", when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> "bonded"
            BluetoothDevice.BOND_BONDING -> "bonding"
            else -> "none"
        })
        .put("device_type", when (device.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
            BluetoothDevice.DEVICE_TYPE_LE -> "ble"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
            else -> "unknown"
        })

    private fun safeName(device: BluetoothDevice): String? = runCatching { device.name }.getOrNull()

    private fun scanPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun connectPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyList()
    }

    private fun ensurePermissions(permissions: List<String>): Boolean {
        val missing = permissions.filterNot(::hasPermission)
        if (missing.isEmpty()) return true
        val result = runBlocking { OffloadPermissionManager.requestAndroidPermission(missing) }
        return result == OffloadPermissionManager.AndroidPermissionResult.GRANTED && missing.all(::hasPermission)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun ok(body: String, args: OffloadArgs) =
        NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")

    private fun error(code: String, message: String, args: OffloadArgs, exitCode: Int = 1): NativeOffloadResult {
        val body = JSONObject().put("error", code).put("message", message).toString()
        return NativeOffloadResult(exitCode, OffloadOutput.formatBody(body, args) + "\n")
    }

    companion object {
        private const val HELP = """android-bluetooth - classic Bluetooth and BLE toolkit

Usage:
  android-bluetooth status
  android-bluetooth paired
  android-bluetooth scan [--mode classic|ble|both] [--timeout 16] [--max 100]
  android-bluetooth pair --address XX:XX:XX:XX:XX:XX
  android-bluetooth enable | disable

Discovery uses Android's native APIs. Pairing may require a system confirmation.
State changes require authorized Shizuku/AXManager. This tool does not bypass
Android's runtime permissions or pairing consent UI.
"""
    }
}

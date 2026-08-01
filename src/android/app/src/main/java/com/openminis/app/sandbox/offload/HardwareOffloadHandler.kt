package com.openminis.app.sandbox.offload

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Read-only hardware inventory and sensor sampling available to an ordinary app. */
class HardwareOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val argv = request.argv.drop(1)
        val args = OffloadArgs(argv, setOf("compact", "quiet", "q"))
        if (argv.isEmpty() || argv.first() in setOf("help", "--help", "-h")) return ok(HELP, args)
        OffloadGate.enforce("hardware_cli", "android-hardware", args, request)?.let { return it }
        return try {
            when (val sub = args.positional.firstOrNull()) {
                "all" -> all(args)
                "sensors" -> sensors(args)
                "sensor-sample" -> sensorSample(args)
                "usb" -> usb(args)
                "nfc" -> nfc(args)
                "network" -> network(args)
                "cameras" -> cameras(args)
                else -> error("invalid_args", "Unknown subcommand '$sub'", args, 2)
            }
        } catch (t: Throwable) {
            error("operation_failed", t.message ?: t.javaClass.simpleName, args)
        }
    }

    private fun all(args: OffloadArgs): NativeOffloadResult {
        val data = JSONObject()
            .put("sensors", sensorListJson())
            .put("usb", usbJson())
            .put("nfc", nfcJson())
            .put("network", networkJson())
            .put("cameras", cameraJson())
        return ok(data.toString(2), args)
    }

    private fun sensors(args: OffloadArgs) = ok(sensorListJson().toString(2), args)

    private fun sensorListJson(): JSONObject {
        val manager = context.getSystemService(SensorManager::class.java)
        val rows = manager?.getSensorList(Sensor.TYPE_ALL).orEmpty()
        return JSONObject()
            .put("count", rows.size)
            .put("items", JSONArray().apply {
                rows.forEach { sensor ->
                    put(JSONObject()
                        .put("type", sensor.type)
                        .put("string_type", sensor.stringType)
                        .put("name", sensor.name)
                        .put("vendor", sensor.vendor)
                        .put("version", sensor.version)
                        .put("power_ma", sensor.power)
                        .put("max_range", sensor.maximumRange)
                        .put("resolution", sensor.resolution)
                        .put("min_delay_us", sensor.minDelay)
                        .put("wake_up", if (Build.VERSION.SDK_INT >= 21) sensor.isWakeUpSensor else false))
                }
            })
    }

    private fun sensorSample(args: OffloadArgs): NativeOffloadResult {
        val manager = context.getSystemService(SensorManager::class.java)
            ?: return error("unsupported", "Sensor service is unavailable", args)
        val requested = args.get("type") ?: args.positional.getOrNull(1)
            ?: return error("invalid_args", "sensor-sample requires --type <integer|stringType|name>", args, 2)
        val sensor = findSensor(manager, requested)
            ?: return error("not_found", "Sensor '$requested' was not found", args)
        val count = (args.getInt("count") ?: 1).coerceIn(1, 100)
        val timeoutMs = (args.getLong("timeout-ms") ?: 5_000L).coerceIn(250L, 30_000L)
        val samples = mutableListOf<JSONObject>()
        val latch = CountDownLatch(count)
        val thread = HandlerThread("minis-sensor-sample").apply { start() }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                synchronized(samples) {
                    if (samples.size >= count) return
                    samples.add(JSONObject()
                        .put("timestamp_ns", event.timestamp)
                        .put("accuracy", event.accuracy)
                        .put("values", JSONArray(event.values.toList())))
                    latch.countDown()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = manager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL,
            Handler(thread.looper),
        )
        if (registered) latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        manager.unregisterListener(listener)
        thread.quitSafely()
        val snapshot = synchronized(samples) { samples.toList() }
        val data = JSONObject()
            .put("sensor", sensor.name)
            .put("type", sensor.type)
            .put("string_type", sensor.stringType)
            .put("requested_count", count)
            .put("count", snapshot.size)
            .put("timed_out", snapshot.size < count)
            .put("samples", JSONArray().apply { snapshot.forEach { put(it) } })
        return if (registered) ok(data.toString(2), args)
        else error("operation_failed", "Sensor listener registration failed", args)
    }

    private fun findSensor(manager: SensorManager, requested: String): Sensor? {
        requested.toIntOrNull()?.let { type -> manager.getDefaultSensor(type)?.let { return it } }
        return manager.getSensorList(Sensor.TYPE_ALL).firstOrNull {
            it.stringType.equals(requested, ignoreCase = true) ||
                it.name.equals(requested, ignoreCase = true) ||
                it.name.contains(requested, ignoreCase = true)
        }
    }

    private fun usb(args: OffloadArgs) = ok(usbJson().toString(2), args)

    private fun usbJson(): JSONObject {
        val manager = context.getSystemService(UsbManager::class.java)
        val rows = manager?.deviceList?.values.orEmpty().sortedBy { it.deviceName }
        return JSONObject()
            .put("host_supported", manager != null)
            .put("count", rows.size)
            .put("devices", JSONArray().apply {
                rows.forEach { device ->
                    put(JSONObject()
                        .put("device_name", device.deviceName)
                        .put("device_id", device.deviceId)
                        .put("vendor_id", device.vendorId)
                        .put("product_id", device.productId)
                        .put("device_class", usbClassName(device.deviceClass))
                        .put("device_subclass", device.deviceSubclass)
                        .put("device_protocol", device.deviceProtocol)
                        .put("manufacturer", runCatching { device.manufacturerName }.getOrNull() ?: JSONObject.NULL)
                        .put("product", runCatching { device.productName }.getOrNull() ?: JSONObject.NULL)
                        .put("version", runCatching { device.version }.getOrNull() ?: JSONObject.NULL)
                        .put("serial", if (manager?.hasPermission(device) == true) {
                            runCatching { device.serialNumber }.getOrNull() ?: JSONObject.NULL
                        } else JSONObject.NULL)
                        .put("permission_granted", manager?.hasPermission(device) == true)
                        .put("interfaces", JSONArray().apply {
                            for (i in 0 until device.interfaceCount) {
                                val intf = device.getInterface(i)
                                put(JSONObject()
                                    .put("id", intf.id)
                                    .put("class", usbClassName(intf.interfaceClass))
                                    .put("subclass", intf.interfaceSubclass)
                                    .put("protocol", intf.interfaceProtocol)
                                    .put("endpoints", intf.endpointCount))
                            }
                        }))
                }
            })
    }

    private fun nfc(args: OffloadArgs) = ok(nfcJson().toString(2), args)

    private fun nfcJson(): JSONObject {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        return JSONObject()
            .put("supported", adapter != null)
            .put("enabled", runCatching { adapter?.isEnabled == true }.getOrDefault(false))
            .put("secure_nfc_supported", if (Build.VERSION.SDK_INT >= 29) {
                runCatching { adapter?.isSecureNfcSupported == true }.getOrDefault(false)
            } else false)
            .put("note", "Tag I/O requires a foreground Activity and user-present tag; status is available here")
    }

    private fun network(args: OffloadArgs) = ok(networkJson().toString(2), args)

    private fun networkJson(): JSONObject {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager?.activeNetwork
        val caps = active?.let { manager.getNetworkCapabilities(it) }
        val link = active?.let { manager.getLinkProperties(it) }
        val interfaces = JSONArray()
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { intf ->
            interfaces.put(JSONObject()
                .put("name", intf.name)
                .put("display_name", intf.displayName)
                .put("up", runCatching { intf.isUp }.getOrDefault(false))
                .put("loopback", runCatching { intf.isLoopback }.getOrDefault(false))
                .put("mtu", runCatching { intf.mtu }.getOrDefault(0))
                .put("addresses", JSONArray().apply {
                    intf.inetAddresses.toList().forEach { put(it.hostAddress) }
                }))
        }
        return JSONObject()
            .put("active", active != null)
            .put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("metered", manager?.isActiveNetworkMetered ?: false)
            .put("transports", JSONArray().apply {
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) put("wifi")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) put("cellular")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) put("ethernet")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) put("vpn")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) put("bluetooth")
            })
            .put("dns_servers", JSONArray().apply { link?.dnsServers.orEmpty().forEach { put(it.hostAddress) } })
            .put("routes", JSONArray().apply { link?.routes.orEmpty().forEach { put(it.toString()) } })
            .put("interfaces", interfaces)
    }

    private fun cameras(args: OffloadArgs) = ok(cameraJson().toString(2), args)

    private fun cameraJson(): JSONObject {
        val manager = context.getSystemService(CameraManager::class.java)
        val ids = runCatching { manager?.cameraIdList?.toList().orEmpty() }.getOrDefault(emptyList())
        return JSONObject().put("count", ids.size).put("camera_ids", JSONArray(ids))
    }

    private fun usbClassName(value: Int): String = when (value) {
        UsbConstants.USB_CLASS_AUDIO -> "audio"
        UsbConstants.USB_CLASS_COMM -> "communications"
        UsbConstants.USB_CLASS_HID -> "hid"
        UsbConstants.USB_CLASS_MASS_STORAGE -> "mass_storage"
        UsbConstants.USB_CLASS_VIDEO -> "video"
        UsbConstants.USB_CLASS_WIRELESS_CONTROLLER -> "wireless_controller"
        UsbConstants.USB_CLASS_PER_INTERFACE -> "per_interface"
        else -> value.toString()
    }

    private fun ok(body: String, args: OffloadArgs) =
        NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")

    private fun error(code: String, message: String, args: OffloadArgs, exitCode: Int = 1): NativeOffloadResult {
        val body = JSONObject().put("error", code).put("message", message).toString()
        return NativeOffloadResult(exitCode, OffloadOutput.formatBody(body, args) + "\n")
    }

    companion object {
        private const val HELP = """android-hardware - Android hardware inventory and sampling

Usage:
  android-hardware all
  android-hardware sensors
  android-hardware sensor-sample --type <id|stringType|name> [--count 1] [--timeout-ms 5000]
  android-hardware usb
  android-hardware nfc
  android-hardware network
  android-hardware cameras

USB lists attached devices and whether Minis has an Android USB grant. NFC tag
transceive is foreground/user-presence driven and is not faked by this CLI.
Privileged/raw operations remain available through android-shizuku-cli and
android-root-cli.
"""
    }
}

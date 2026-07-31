package com.openminis.app.sandbox.offload

/** Pure command construction shared by Android research-tool handlers and JVM tests. */
internal object ResearchToolCommands {
    val wifiSecurityModes: Set<String> = linkedSetOf("open", "owe", "wpa2", "wpa3")

    fun wifiConnect(ssid: String, security: String, password: String?): List<String> {
        require(ssid.isNotBlank()) { "SSID must not be blank" }
        require(security in wifiSecurityModes) { "Unsupported Wi-Fi security mode" }
        require(security in setOf("open", "owe") || !password.isNullOrEmpty()) {
            "$security requires a password"
        }
        return buildList {
            addAll(listOf("cmd", "wifi", "connect-network", ssid, security))
            if (!password.isNullOrEmpty()) add(password)
        }
    }

    /** Remove only options interpreted by android-root-cli; preserve the raw shell payload. */
    fun stripRootOptions(raw: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < raw.size) {
            val item = raw[i]
            when {
                item.startsWith("--backend=") || item.startsWith("--timeout-ms=") -> Unit
                item == "--backend" || item == "--timeout-ms" -> i++
                item in setOf("--compact", "--quiet", "-q") -> Unit
                else -> out.add(item)
            }
            i++
        }
        return out
    }

    fun isBluetoothAddress(value: String): Boolean =
        Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(value)
}

package com.openminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchToolCommandsTest {
    @Test
    fun wifiConnectPreservesSsidAndCredentialForNativeCall() {
        val command = ResearchToolCommands.wifiConnect(
            ssid = "Lab Network",
            security = "wpa3",
            password = "correct horse battery staple",
        )

        assertEquals(
            listOf(
                "cmd",
                "wifi",
                "connect-network",
                "Lab Network",
                "wpa3",
                "correct horse battery staple",
            ),
            command,
        )
    }

    @Test
    fun securedWifiRequiresPasswordButOpenWifiDoesNot() {
        assertThrows(IllegalArgumentException::class.java) {
            ResearchToolCommands.wifiConnect("Lab", "wpa2", null)
        }
        assertEquals(
            listOf("cmd", "wifi", "connect-network", "Guest", "open"),
            ResearchToolCommands.wifiConnect("Guest", "open", null),
        )
    }

    @Test
    fun rootWrapperOptionsAreRemovedWithoutFilteringShellCommand() {
        val payload = ResearchToolCommands.stripRootOptions(
            listOf(
                "--backend", "su",
                "--timeout-ms=120000",
                "iptables", "-L", "&&", "id", "|", "tee", "/data/local/tmp/result",
            ),
        )

        assertEquals(
            listOf("iptables", "-L", "&&", "id", "|", "tee", "/data/local/tmp/result"),
            payload,
        )
    }

    @Test
    fun bluetoothAddressValidationIsStrictAndCaseInsensitive() {
        assertTrue(ResearchToolCommands.isBluetoothAddress("AA:bb:01:23:45:fF"))
        assertFalse(ResearchToolCommands.isBluetoothAddress("AA-BB-01-23-45-FF"))
        assertFalse(ResearchToolCommands.isBluetoothAddress("not-an-address"))
    }
}

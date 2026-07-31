# Android research toolkit fork

This fork exposes the phone's Android framework and existing privileged
backends to the Minis agent. The important boundary is the Android host:
commands such as `nmcli`, `iw`, or desktop BlueZ tools inside PRoot do not own
the phone's radios. Use the native `android-*` commands below instead.

## Capability layers

1. Ordinary Android APIs provide Wi-Fi/Bluetooth discovery, paired-device
   inventory, sensors, USB inventory, NFC status, cameras, and active-network
   information.
2. Shizuku started through ADB provides the same effective privilege as
   `adb shell` (normally UID 2000). `android-shizuku-cli exec` is the raw
   escape hatch for any system command not covered by a structured command.
3. AXManager/Shizuku started as root, or an existing `su`, provides UID 0 to
   `android-root-cli exec`. No command allow-list is applied by this fork.
4. `android-a11y-cli` supplies cross-application UI inspection and automation.

The app does not contain a root exploit and cannot silently grant itself an
Android dangerous permission. The Android permission dialog, Bluetooth pairing
dialog, Accessibility enablement, Shizuku authorization, and root-manager grant
are operating-system/user consent boundaries and still apply.

## New commands

### Wi-Fi

```sh
android-wifi status
android-wifi scan --timeout 12 --max 100
android-wifi saved
android-wifi enable
android-wifi disable
android-wifi connect --ssid "Lab Network" --security wpa3 --password "$WIFI_PASSWORD"
android-wifi forget --network-id 4
```

Scanning uses `WifiManager`. Modern Android versions require Location
permission (and Location services enabled) for scan results. Android can
throttle repeated scans; the JSON response identifies cached/throttled data.
The password is passed to Android's `cmd wifi` process but is not copied into
the tool response.

### Bluetooth and BLE

```sh
android-bluetooth status
android-bluetooth paired
android-bluetooth scan --mode both --timeout 16 --max 100
android-bluetooth pair --address AA:BB:CC:DD:EE:FF
android-bluetooth enable
android-bluetooth disable
```

Android 12 and newer request `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` at
runtime. Classic discovery and BLE scanning return structured JSON. Pairing can
raise Android's own confirmation dialog. State changes use Shizuku/AXManager.

### Hardware and connections

```sh
android-hardware all
android-hardware sensors
android-hardware sensor-sample --type android.sensor.accelerometer --count 10
android-hardware usb
android-hardware nfc
android-hardware network
android-hardware cameras
```

USB inventory reports whether Minis has a per-device USB permission grant.
NFC tag I/O itself is foreground and user-presence driven on Android; this CLI
reports the adapter state. A future foreground reader UI can be layered on top
without changing the agent protocol.

### Unrestricted privileged shells

```sh
android-shizuku-cli service status
android-shizuku-cli exec 'dumpsys wifi'
android-shizuku-cli exec 'cmd package list packages -3'

android-root-cli status
android-root-cli exec --backend auto --timeout-ms 120000 'id && getenforce'
android-root-cli exec 'iptables -L -n -v'
```

`android-root-cli` prefers a Shizuku-compatible service already running as UID
0 and otherwise invokes an existing `su -c`. It does not filter the command.
Output is capped at 1 MiB per stream so an unbounded command cannot exhaust the
app process; the command itself may write arbitrarily large data to a file.

## Credentials and privacy mode

This research fork permits the agent to read and pass credentials when a task
explicitly requires them. Environment-variable Privacy Mode now defaults to
off for new installations. It remains a user-controlled setting and can be
turned on to redact environment-variable values before shell output is sent
back to the model.

Credentials are capability inputs, not automatically public values. The agent
prompt asks the model not to repeat a credential in unrelated prose or send it
to an unrelated destination. Existing application log redaction remains in
place; this does not prevent a requested command from using the real value.

## Initial device setup

1. Install the research APK.
2. Open **Settings > Permissions**. The research CLIs default to **Bypass** at
   the Minis agent gate; you may still downgrade any individual tool.
3. Grant runtime Wi-Fi/Bluetooth permissions when Android asks.
4. Enable the Minis Accessibility service if cross-app UI control is needed.
5. Install/start Shizuku for ADB-shell capabilities, or AXManager/root-started
   Shizuku for UID 0, and authorize Minis.
6. Verify from a chat or terminal:

```sh
android-wifi status
android-bluetooth status
android-shizuku-cli service status
android-root-cli status
```

## Scope

The structured commands cover common radio and hardware workflows. The raw
Shizuku and root shells deliberately cover the long tail: package manager,
services, settings databases, `dumpsys`, network namespaces/firewall, device
nodes, and vendor commands present on the phone. Hardware absent from a device,
SELinux policy, vendor firmware, and Android APIs that require a foreground
user interaction remain real platform constraints rather than gateway limits.

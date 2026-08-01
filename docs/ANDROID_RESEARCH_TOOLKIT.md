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
5. The official Termux `RUN_COMMAND` service connects Minis to a separately
   installed Termux or Kali NetHunter Rootless environment and returns stdout,
   stderr, and the exit code to the agent.

The app does not contain a root exploit and cannot silently grant itself an
Android dangerous permission. The Android permission dialog, Bluetooth pairing
dialog, Accessibility enablement, Shizuku authorization, and root-manager grant
are operating-system/user consent boundaries and still apply.

## New commands

The agent receives these capabilities through the structured
`android_native` tool. It dispatches directly to Android-host handlers and does
not require PRoot. The command names below remain available inside the terminal
for manual use and backwards compatibility. Requests involving phone hardware
must use `android_native`, not Linux desktop utilities such as `nmcli`, `iw`,
`bluetoothctl`, or `lsusb`.

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

### Termux and NetHunter Rootless

```sh
android-termux-cli status
android-termux-cli exec --timeout-ms 120000 'uname -a'
android-termux-cli nethunter --timeout-ms 120000 'cat /etc/os-release'
android-termux-cli nethunter 'aircrack-ng --help'
```

This bridge uses Termux's documented `RUN_COMMAND` Android service; it does not
read Termux's private data directory from the Minis process. Setup has two
independent consent gates:

1. Grant Minis the Android **Run commands in Termux environment** additional
   permission (`com.termux.permission.RUN_COMMAND`).
2. Add `allow-external-apps=true` to
   `~/.termux/termux.properties` inside Termux.

`exec` runs the command with Termux bash. `nethunter` runs it through the
installed NetHunter Rootless launcher as the PRoot root user. Output returned
by Termux is subject to Android Binder limits and Termux's own result cap; the
response reports whether it was truncated.

This adds Linux userland and package availability, not kernel functionality.
The internal Android Wi-Fi chipset still cannot enter monitor mode or inject
frames unless the device kernel and driver expose those features. Use
`android-wifi scan` for ordinary Android discovery and a supported external
adapter/kernel for monitor-mode workflows.

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
6. If Termux/NetHunter integration is needed, enable
   `allow-external-apps=true` in Termux and grant Minis the additional
   **Run commands in Termux environment** permission.
7. Verify from a chat or terminal:

```sh
android-wifi status
android-bluetooth status
android-shizuku-cli service status
android-root-cli status
android-termux-cli status
```

The build now fails before compilation if the Alpine rootfs, the PRoot asset,
or `lib/arm64-v8a/libproot.so` is missing. Always run the documented native
dependency steps before producing an APK; an incomplete shell-less APK is no
longer permitted.

## Scope

The structured commands cover common radio and hardware workflows. The raw
Shizuku and root shells deliberately cover the long tail: package manager,
services, settings databases, `dumpsys`, network namespaces/firewall, device
nodes, and vendor commands present on the phone. Hardware absent from a device,
SELinux policy, vendor firmware, and Android APIs that require a foreground
user interaction remain real platform constraints rather than gateway limits.

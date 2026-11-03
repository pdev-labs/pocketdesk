# PocketDesk Legacy (Android 5.0 – 7.1)

Phones that never received Android 8 still work. **PocketDesk Legacy** is the
same app, built for **Android 5.0 (API 21) through 7.1 (API 25)**, with the
platform calls that only exist on newer Android replaced by their older
equivalents.

If your phone runs Android 8.0 or newer, use the normal `pocketdesk.apk`
instead — it is smaller and uses the newer notification, audio and installer
APIs.

## What you get

Everything the normal app does:

* automatic discovery of computers on the same network, and QR / 8-character
  pairing,
* direct-touch and touchpad input, gestures, modifier chips and the phone’s own
  keyboard,
* audio to the phone, screen modes, quality presets, full screen and the
  floating controls,
* USB-cable mode (the host needs `adb`),
* second screen where the host supports it (Hyprland).

What is different:

* a **separate installation** — package `dev.pocketdesk.legacy`, labelled
  *PocketDesk Legacy* with its own icon, so it can live next to the normal app,
* a **static dark palette** (dynamic Material You colours need Android 12),
* pre-Oreo notifications, foreground service, runtime permissions and APK
  installer,
* its own update channel, `legacy-latest`, so a legacy build never shadows the
  normal app’s update.

It is signed with the **same permanent update key** as the normal app, so
updates install over it and the certificate never changes.

## Download

* Direct APK:
  <https://github.com/pdev-labs/pocketdesk/releases/download/legacy-latest/pocketdesk-legacy.apk>
* Release page (a prerelease, on purpose):
  <https://github.com/pdev-labs/pocketdesk/releases/tag/legacy-latest>
* Size: ~16 MB. Minimum Android 5.0. No Play Store listing.

Check the download against `latest-legacy.json` (published next to the APK):

```bash
sha256sum pocketdesk-legacy.apk
# compare with the "sha256" field:
curl -sL https://github.com/pdev-labs/pocketdesk/releases/download/legacy-latest/latest-legacy.json
```

## Install

The APK is sideloaded, so Android has to allow installs from the app you open it
with (Files, Downloads, Chrome, …).

### Android 5.0 – 5.1 (Lollipop)

1. **Settings → Security → Unknown sources** — turn it on (accept the warning).
2. Download the APK, or copy it to the phone.
3. Open **Downloads** (or the notification) and tap `pocketdesk-legacy.apk`.
4. **Install**.

### Android 6.0 (Marshmallow)

1. **Settings → Security → Unknown sources** — turn it on.
2. Open the APK from Downloads and tap **Install**.
3. On first launch, grant **Camera** when asked (needed for QR scanning; you can
   also type the code by hand and skip it).

### Android 7.0 – 7.1 (Nougat)

Android 7 moved the switch to a per-app permission:

1. **Settings → Apps → Special access → Install unknown apps** (on some phones:
   **Settings → Security → Unknown sources**).
2. Allow the app you will open the APK with — Files, Downloads, Chrome or your
   file manager.
3. Open `pocketdesk-legacy.apk` and tap **Install**.

### If Play Protect shows a warning

It appears for every sideloaded app. Choose **Install anyway** (or **More
details → Install anyway**). PocketDesk is not distributed through Play.

## First connection

The host is the same `pocketdesk` binary as the normal app; nothing changes on
the computer.

1. Start the host. It prints a QR code and an 8-character code such as
   `K7M4-QX2B`.
2. On the phone, open **PocketDesk Legacy**:
   * **Scan QR code** — needs the camera permission, or
   * type the computer’s address, port `8765` and the code.
3. Hardware keyboard? Leave **USB cable** off for Wi-Fi. For a cable, tick
   **USB cable (fast)**; the host must have `adb` and the cable must allow
   debugging.

## Updates

**Settings → Check for updates** reads `latest-legacy.json` from the
`legacy-latest` prerelease. When a newer version exists it downloads the APK,
checks its SHA-256 and signing certificate, and opens the system installer.

You can also download the APK again from the link above and install it over the
old one — the package and key are unchanged, so your saved computers, quality
presets and settings stay.

> On a very old phone with an outdated trust store, the HTTPS update check can
> fail. The app says so; download the APK manually in that case. Streaming to
> the host is plain HTTP on your own network and is unaffected.

## Troubleshooting

| Symptom | What to do |
| --- | --- |
| **App not installed** | An older build signed with a different key is installed (e.g. a debug APK). Uninstall *PocketDesk Legacy*, then install the release APK. Also check free storage. |
| **There was a problem parsing the package** | The download is truncated. Re-download and compare the SHA-256 with `latest-legacy.json`. |
| **No “Unknown sources” entry** | On Android 7 use **Settings → Apps → Special access → Install unknown apps** and allow your file manager or browser. |
| **QR scanner does not open** | Grant Camera in **Settings → Apps → PocketDesk Legacy → Permissions** (Android 6+). On 5.x the permission is granted at install time — reinstall if it was denied. |
| **“Check for updates” fails** | Download the APK manually from the link above; old trust stores can reject GitHub’s certificate. |
| **Cannot connect** | Phone and computer on the same network, the host’s firewall allows TCP 8765, and the code matches. USB mode additionally needs `adb` on the host. |
| **Stream is slow** | Pick **Low data** (10 fps, q45, 0.75×) in **⋯ → Quality & frame rate**. Older hardware decodes a smaller frame far more comfortably. |
| **Two PocketDesk icons** | Expected: the normal app and *PocketDesk Legacy* are separate packages and can coexist. |

## Uninstall

Uninstalling *PocketDesk Legacy* does not touch the normal app or its saved
computers. Updates stop with the app; the host keeps working for every other
client.

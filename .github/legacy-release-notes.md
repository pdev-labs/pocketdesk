PocketDesk **Legacy** — for Android 5.0 – 7.1 (API 21+).

This channel exists for phones that are stuck below Android 8. It is the same
app as the main release, with the newer platform calls replaced by their older
equivalents:

* **Audio** — `AudioTrack` and audio focus fall back to the pre-Android 6 API.
* **Notifications** — no channels below Android 8; the session notification and
  its Stop confirmation work as before.
* **Permissions** — runtime permission requests are skipped, because Android 5
  grants camera access at install time.
* **Updates** — the installer, SHA-256 and signature checks are the same; on
  Android 5/6 the “install unknown apps” screen is the old security settings one.

The **interface** is rebuilt around Material You: Home / Devices / Settings tabs, a
discovery-first connect screen, bottom sheets for quality/input/audio/keys, and a
floating session pill that fades out on its own. On Android 12+ the palette follows
the wallpaper; Android 5–11 use the built-in dark seed.

Everything else is unchanged: LAN discovery, QR pairing and remembered devices,
direct touch and touchpad gestures, natural scrolling, keyboard combos with the
phone keyboard, screen fit/fill/stretch, audio to the phone, second screen, USB
cable transport, and background sessions.

Notes:

* Install **`pocketdesk-legacy.apk`** — it is signed with the same permanent
  update key as the main app (certificate
  `A6:19:9D:C0:B7:5A:FD:BE:A0:46:D8:3D:00:12:54:35:DF:60:AB:95:1D:83:54:C3:CF:4C:4E:ED:D1:7B:B2:DA`).
  Debug-signed APKs are no longer published, because the self-updater refuses
  to install an APK signed by a different key. If you installed an earlier
  `-debug` build, uninstall it once.
* Separate package: `dev.pocketdesk.legacy`, labelled **PocketDesk Legacy**, so
  it can live next to the normal app.
* Keyboard input accepts both `commitText` and key-event typing, which is what
  older keyboards on Android 5–7 use.
* Every push to the `legacy` branch republishes the APK, `latest-legacy.json`
  and checksums below, and the app follows this tag when checking for updates.
* This release is marked **prerelease** on purpose: the normal app keeps
  updating from `releases/latest`.

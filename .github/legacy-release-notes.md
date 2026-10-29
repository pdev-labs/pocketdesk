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

Everything else is unchanged: LAN discovery, QR pairing and remembered devices,
direct touch and touchpad gestures, natural scrolling, keyboard combos with the
phone keyboard, screen fit/fill/stretch, audio to the phone, second screen, USB
cable transport, and background sessions.

Notes:

* Separate package: `dev.pocketdesk.legacy`, labelled **PocketDesk Legacy**, so
  it can live next to the normal app.
* Signed with the same update key as the main app.
* Every push to the `legacy` branch republishes the APK, `latest-legacy.json`
  and checksums below, and the app follows this tag when checking for updates.
* This release is marked **prerelease** on purpose: the normal app keeps
  updating from `releases/latest`.

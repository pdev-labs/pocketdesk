# Roadmap

## v0.1 — shipped

* Linux host: `grim` (wlroots Wayland) and `ffmpeg` (X11, plus Windows/macOS capture).
* Absolute uinput pointer + full US keyboard on Linux.
* Android app: JPEG stream, touch gestures, soft keyboard, modifier chips, auto-reconnect, view-only detection.
* Token auth, read-only mode, test-pattern backend.
* CI: APK + host binaries for Linux/Windows/macOS, tagged releases with checksums.

## v0.2 — lower bandwidth, real TLS

* **H.264 via MediaCodec** — host-side `libx264` (or VAAPI/NVENC), phone-side hardware decoder, `codec: "h264"` in `hello`, automatic fallback to JPEG for old phones. Target: 1080p at ~3 Mbit/s.
* **TLS + pinning** — self-signed cert generated on first run, fingerprint shown next to the token, app pins it after pairing. Removes the "trusted LAN only" caveat.
* **Clipboard sync** — text only, both directions, opt-in.
* **QR pairing** — the host prints a QR of `ws://ip:port#token`; the app scans it. Removes typing 64 hex characters.

## v0.3 — every desktop

* **Windows input** — `SendInput` for pointer and keyboard (the capture path already works through `gdigrab`).
* **macOS input** — `CGEvent` plus the Accessibility permission prompt (capture already works through `avfoundation`).
* **Native Wayland capture** — a `wlr-screencopy` client in-process instead of `grim` per frame; roughly doubles the frame ceiling.
* **XDG portal capture** — GNOME and KDE on Wayland, using the PipeWire ScreenCast portal with a one-time picker dialog.

## v0.4 — a real remote desktop

* **Audio** — PulseAudio/PipeWire monitor source, Opus, phone speaker.
* **Multi-monitor switching** — list monitors in the app, switch without restarting.
* **File drop** — send a file from the phone into the session.
* **Wake-on-LAN** — wake a sleeping machine from the app before connecting.

## Ideas / not committed

* WebRTC transport for NAT traversal without a VPN.
* Hardware cursor layer (send the cursor bitmap separately, hide it from capture) to cut input latency visually.
* Adaptive bitrate driven by phone-reported decode time.
* Host-side privacy switch: blank the physical screen while streaming.
* Third-party relay for people who cannot install Tailscale — deliberately low priority; self-hosting is the point.

## Non-goals

* A cloud service, user accounts, or a web dashboard.
* Root-level remote administration.
* Being an alternative to SSH for shell work — PocketDesk is for the GUI you are already sitting in front of.

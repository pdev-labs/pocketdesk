# Security

PocketDesk gives whoever holds the pairing token full control of a desktop session. Treat the token like an SSH key.

## What is protected

* **Every screen byte requires the token.** `/ws` compares it in constant time (`subtle::ConstantTimeEq`), rejects missing/wrong tokens with `401`, and never falls back to anonymous access.
* **Code at rest** — `~/.config/pocketdesk/token`, created with mode `0600` inside `~/.config/pocketdesk`.
* **Code strength** — 8 characters from a 31-symbol alphabet (~40 bits), printed as `K7M4-QX2B`.
  Six free attempts per address, then a lock that starts at one minute and doubles to
  fifteen: guessing is not practical over a socket. The QR carries the same code, so
  scanning is exactly as strong as typing it.
* **No third-party services.** No telemetry, no relay, no accounts. The host connects to nothing; only the phone dials in.

## What is *not* protected in v0.1

**Traffic is plain `ws://`, not `wss://`.** Anyone on the same network who can sniff packets can read the screen and the token.

That is acceptable on a trusted home LAN, and it is why the recommended way to use PocketDesk from anywhere is a private network:

* **Tailscale / WireGuard / ZeroTier** — encrypted, authenticated, no ports exposed.
* **SSH tunnel** — `ssh -L 8765:127.0.0.1:8765 user@host` plus `pocketdesk --bind 127.0.0.1`.

Do **not** port-forward `8765` to the public internet. If you must, put a TLS-terminating reverse proxy in front (Caddy with an internal CA, or nginx with a client certificate) and read the roadmap: native TLS with certificate pinning is the next security milestone.

## Recommendations

| Situation | Do this |
| --- | --- |
| Home LAN | Defaults are fine. Use a WPA2/WPA3 network you trust. |
| Café / hotel / shared Wi-Fi | Tailscale or an SSH tunnel. Assume the network is hostile. |
| Public server | Don't. If you need it, `--bind 127.0.0.1` behind a VPN. |
| Shared machine | `--read-only`, or a dedicated token you rotate afterwards. |
| Lost phone | Delete `~/.config/pocketdesk/token` and restart the host. The old token stops working immediately. |
| Auditing | `pocketdesk --verbose` logs every connection and rejection with the peer address. |

## The pairing code

The code is short so it can be typed, and the QR exists so it rarely has to be.
It is *not* a password you should reuse elsewhere: rotating it is one command
(delete the file, restart the host). Older 64-character tokens keep working,
and the comparison accepts either form.

## Release signing and in-app updates

The APK is signed with one long-lived key (RSA 4096, self-signed, PKCS12). Android
refuses to update an app with an APK signed by a different key, which is exactly the
guarantee the updater relies on:

```
latest.json ──► SHA-256 of pocketdesk.apk
     │
     ├── download ──► hash matches?
     └── signing certificate == installed app's certificate?  (refuse otherwise)
                                   │
                                   ▼
                        Android package installer
```

* The key lives in GitHub repository secrets, never in the repository. A local copy with
  its password is kept outside the tree; **losing it means existing installs can never be
  updated** (they would have to be uninstalled).
* CI runs `apksigner verify --print-certs` on every tag and publishes the digest, so the
  same certificate can be checked by hand.
* The update check is the only outbound request the app makes, and only to GitHub. It
  downloads nothing unless a newer `versionCode` is published.
* `latest.json` is public and carries no secrets — only a version, size and hash.
* If the checksum or certificate does not match, the file is deleted and the installer is
  never opened.

## What a stolen token grants

* Viewing the screen (all windows, notifications, password managers left open).
* Moving the pointer, clicking, and typing — i.e. running commands as your user.
* Nothing else: the host does not read files, expose a shell, or persist beyond the process. It cannot escalate to root by itself.

Mitigations already in the host:

* `--read-only` refuses input at the protocol layer, not just in the UI.
* Input messages that fail validation produce an error, never a crash.
* The WebSocket has no compression and no request state; there is no deserialisation surface beyond the JSON enum.
* Capture and input run with your user's privileges only. uinput access is granted through a dedicated group and a udev rule, not by running the host as root.

## On the phone

* The token is stored in the app's private `SharedPreferences` (not backed up: `allowBackup="false"`).
* The app talks only to the host you type in; there is no analytics SDK and no network code beyond OkHttp to that address.
* Screenshots of the app show your desktop — treat them as sensitive.

## Reporting a problem

Open a private security advisory on [GitHub](https://github.com/pdev-labs/pocketdesk/security/advisories/new) rather than a public issue.

# Security

PocketDesk gives whoever holds the pairing token full control of a desktop session. Treat the token like an SSH key.

## What is protected

* **Every screen byte requires the token.** `/ws` compares it in constant time (`subtle::ConstantTimeEq`), rejects missing/wrong tokens with `401`, and never falls back to anonymous access.
* **Token at rest** — `~/.config/pocketdesk/token`, created with mode `0600` inside `~/.config/pocketdesk`.
* **Token strength** — 32 bytes from `OsRng`, printed as 64 hex characters.
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

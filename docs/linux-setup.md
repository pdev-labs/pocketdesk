# Linux host setup

## Dependencies

| Need | Arch | Debian/Ubuntu | Fedora |
| --- | --- | --- | --- |
| Wayland capture | `pacman -S grim` | `apt install grim` | `dnf install grim` |
| X11 / fallback capture | `pacman -S ffmpeg` | `apt install ffmpeg` | `dnf install ffmpeg` |
| Phone control | `scripts/setup-linux-input.sh` | same | same |

Only one capture tool is required: `grim` on wlroots Wayland, `ffmpeg` on X11. The binary itself has no runtime dependencies beyond libc.

## Which display server am I on?

```bash
echo "session: $XDG_SESSION_TYPE"
echo "wayland: $WAYLAND_DISPLAY  x11: $DISPLAY"
```

| Environment | Backend | Status |
| --- | --- | --- |
| Hyprland, Sway, river, Wayfire (wlroots) | `grim` | supported |
| X11 (any desktop) | `ffmpeg` + `x11grab` | supported |
| GNOME / KDE on Wayland | — | not yet — needs an XDG portal capture backend ([roadmap](roadmap.md)) |
| Headless / no display | `--capture test` | for testing the app only |

XWayland (`DISPLAY=:0`) **cannot** capture native Wayland windows; `ffmpeg -f x11grab` would only see X clients. Use `grim` instead.

## Remote input (once per machine)

```bash
sudo ./scripts/setup-linux-input.sh
```

What it does:

1. creates the `pocketdesk-input` group;
2. adds your user to it;
3. installs `/etc/udev/rules.d/99-pocketdesk-uinput.rules` granting that group read/write on `/dev/uinput`;
4. loads the `uinput` module now and at boot.

Then log out and back in (or `newgrp pocketdesk-input`). Verify:

```bash
ls -l /dev/uinput          # crw-rw---- root pocketdesk-input
./pocketdesk               # banner should say: input  enabled
```

If you would rather not grant this, run `pocketdesk --read-only`: the screen still streams, input is refused by the host (not merely greyed out in the app).

## Typical runs

```bash
# Hyprland/Sway, focused monitor, defaults
./pocketdesk

# X11, 25 fps, 0.75 scale for a weak Wi-Fi link
DISPLAY=:0 ./pocketdesk --capture ffmpeg --fps 25 --scale 0.75

# stream a specific monitor
./pocketdesk --output HDMI-A-1

# no capture at all — a moving test pattern for trying the phone app
./pocketdesk --capture test

# loopback only, for an SSH tunnel
./pocketdesk --bind 127.0.0.1
```

The pairing code is stored at `~/.config/pocketdesk/token` (mode `0600`) and reused across runs.
The host prints it as `K7M4-QX2B`, together with a scannable QR code. Rotate it with:

```bash
rm ~/.config/pocketdesk/token && ./pocketdesk   # prints a fresh code + QR
```

No QR in the terminal (CI logs, `tmux` without a TTY, a pipe)? Use `--no-qr`, or read the
pairing URI from the verbose log. The app can still be paired by typing the 8-character code.

## Firewall

The host listens on `0.0.0.0:8765` by default.

```bash
# ufw (Ubuntu)
sudo ufw allow from 192.168.1.0/24 to any port 8765 proto tcp

# firewalld (Fedora)
sudo firewall-cmd --permanent --add-port=8765/tcp && sudo firewall-cmd --reload
```

Check it is actually reachable from the phone's network:

```bash
ss -ltnp | grep 8765
curl http://<host-ip>:8765/healthz     # -> ok
```

## Start at login (systemd user unit)

```bash
mkdir -p ~/.config/systemd/user
cat > ~/.config/systemd/user/pocketdesk.service <<'EOF'
[Unit]
Description=PocketDesk host
After=graphical-session.target
PartOf=graphical-session.target

[Service]
ExecStart=%h/.local/bin/pocketdesk
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable --now pocketdesk
journalctl --user -u pocketdesk -f
```

The unit needs the graphical session's environment. On wlroots desktops it usually inherits `WAYLAND_DISPLAY`/`XDG_RUNTIME_DIR`; if `grim` reports that it cannot connect, add:

```bash
systemctl --user import-environment WAYLAND_DISPLAY XDG_RUNTIME_DIR HYPRLAND_INSTANCE_SIGNATURE
```

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `grim failed: failed to connect to wayland` | started outside the session (cron, ssh without env). Export `WAYLAND_DISPLAY`/`XDG_RUNTIME_DIR`, or run the systemd unit as above. |
| Black or frozen frames | compositor blocks screencopy, or the monitor is DPMS-off. Wake it and retry. |
| `input  disabled — cannot open /dev/uinput` | run the setup script, then re-login. Check `ls -l /dev/uinput`. |
| App shows **view only** | host is `--read-only`, or uinput setup is missing. The exact reason is in the app's toast. |
| Connection refused | wrong IP/port, firewall, or phone on a guest/AP-isolated network. Test `/healthz` from another machine. |
| Pointer lands offset from the touch | multi-monitor layout changed after start, or `--output` points at a different monitor. Restart the host; it re-reads `hyprctl` every 20 s. |
| High latency, stutter | lower `--fps`, `--quality`, or use `--scale 0.75`; check Wi-Fi signal; 2.4 GHz band is often the bottleneck. |
| Frames at 20 fps but phone shows 8 | phone decoder or Wi-Fi. Try `--scale 0.75 --quality 65`. |
| Token lost | `./pocketdesk --print-token` |

# Second screen (virtual monitor)

Mirroring sends a copy of a monitor. **Second screen** gives the phone its own
display instead, so the desktop gets wider and windows can live on the phone:

```text
  eDP-1 (1366×768)                    pocketdesk-phone (1600×720)
  ┌──────────────┐                    ┌───────────────────────┐
  │  your work   │                    │  windows you move here│
  └──────────────┘                    └───────────────────────┘
          └───────────── one desktop, left to right ──────────┘
```

Every phone asks for its own display, sized to that phone, so two extended
phones are two independent monitors rather than one shared one.

## Status by host

| Host | Second screen | Needs |
| --- | --- | --- |
| Linux + Hyprland | **Works today** | `hyprctl`, `grim` |
| Linux + X11 / other Wayland compositors | Mirror only | — |
| Windows | **Mirror only today** — see below | virtual display driver **and** per-monitor capture support in the host |
| macOS | Mirror only | — |

The Windows section explains exactly what to install and what is still missing.

---

## Linux (Hyprland) — works today

### What you need

* Hyprland (any recent version; 0.5x with `hyprctl eval` and older `hyprctl
  keyword` are both handled).
* `grim` in `PATH` (`pacman -S grim`, `apt install grim`). The host uses it for
  every capture, including the virtual monitor.
* PocketDesk started normally; `--capture auto` picks `grim` on Wayland.

```bash
./pocketdesk            # mirror by default
./pocketdesk --extend 1600x900   # start with a virtual monitor
```

### Turning it on

In the app: **⋯ → Use phone as a second screen**. The host creates a headless
output named `pocketdesk-phone-N`, parks it to the right of your desktop, and
starts capturing it. The setting is remembered per phone and re-applied on the
next connection.

Move windows onto it with `Super+Shift+←/→`, or launch apps and drag them over.
The phone is then an ordinary extra monitor: workspaces, full-screen windows and
the pointer all behave normally.

### How it is built

`hyprctl output create headless pocketdesk-phone-N`, then a monitor rule that
sizes it and places it right of the last physical output, then `grim -o
pocketdesk-phone-N`. Input is mapped from the phone's frame into that monitor's
rectangle, so a tap at the top-left of the phone lands at the top-left of the
virtual monitor — not on the laptop panel.

### Troubleshooting

| Symptom | Fix |
| --- | --- |
| “Second screen needs a Hyprland host” | You are on X11, another compositor, or `hyprctl` is not usable. Mirroring still works. |
| The phone shows the laptop panel after toggling | The app remembers per phone; toggle it off and on again, or reconnect. |
| `pocketdesk-phone` left over after a crash | `hyprctl output remove pocketdesk-phone` — do not create your own output with that name. |
| Window lands on the wrong edge | `hyprctl monitors` shows the actual layout; the host places the virtual output right of the last monitor. |

---

## Windows — what to install (and what is still missing)

Windows has no API an application can call to add a monitor. The desktop only
grows if a **virtual display driver** (or a physical dummy plug) presents an
extra display to the OS. Two options:

### Option A — virtual display driver (recommended)

Install an IddCx *indirect display* driver. The widely used open-source one is
**Virtual Display Driver** (`VirtualDisplayDriver/VirtualDisplayDriver` on
GitHub; also distributed as “VDD”). Read its release notes first: depending on
your Windows build it needs test-signing or a signed release, and a reboot.

1. Install the driver, reboot.
2. Add a display sized like the phone, e.g. **1600×900**.
3. In **Settings → System → Display**, set it to **Extend these displays**, arrange
   it to the right of your main screen, and set **Scale = 100%** (mixed DPI makes
   absolute pointer mapping inaccurate).
4. Confirm it appears: `Get-CimInstance -Namespace root\wmi -ClassName WmiMonitorBasicDisplayParams`
   lists the active displays, and Windows Settings shows the extra monitor.

### Option B — dummy HDMI/DisplayPort plug

A hardware EDID emulator costs a few dollars and looks like a real monitor to
Windows. Same result, no driver signing; the desktop extends onto it.

### What PocketDesk still needs on Windows

The current release (0.8.1) **mirrors the whole desktop** and refuses
second-screen mode off Linux. That is deliberate, not a driver problem: the host
has to learn three things before the driver/plug is useful:

1. **Enumerate monitors** — the layout is currently discovered with `hyprctl`
   and falls back to “one screen” everywhere else.
2. **Capture one monitor** — `gdigrab` can capture a rectangle with
   `-offset_x`, `-offset_y` and `-video_size`; the host always captures
   `desktop` (all monitors combined) today.
3. **Select it** — `--extend` / the app toggle must target the virtual monitor
   instead of trying to create a Hyprland output.

The input side is already done: `SendInput` addresses the whole virtual desktop,
and the phone's coordinates are mapped from the captured rectangle, so a tap on
the virtual monitor goes to the virtual monitor.

Once those three pieces land, the flow is identical to Linux: install the
driver/plug, toggle **Use phone as a second screen**, and the phone shows only
that monitor.

> Until then, on Windows use **mirror** mode: the phone shows one of your
> monitors (the whole desktop is captured today, so a multi-monitor desktop
> appears side by side).

---

## macOS

Not supported. Mirroring works through `avfoundation`; macOS has no supported
way for a third-party app to add a display without a kernel extension.

## Tips

* Pick a resolution close to the phone's aspect ratio — the app can still fit,
  fill or stretch, but a matching ratio avoids bars.
* A second screen is a second encode. If the stream stutters, lower the quality
  preset (**⋯ → Quality & frame rate**) rather than the resolution.
* The virtual monitor disappears when the last extended phone disconnects, so a
  crashed session does not leave dead desktop space behind (on Hyprland).

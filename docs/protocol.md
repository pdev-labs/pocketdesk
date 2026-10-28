# Wire protocol

One WebSocket carries both directions.

* **Server → client:** binary messages are complete JPEG frames; text messages are JSON control.
* **Client → server:** text messages only, JSON input events.

## Pairing

The host prints a QR code that encodes everything the app needs:

```
pocketdesk://pair?v=1&h=192.168.1.24&p=8765&t=K7M4QX2B&n=arch
```

Short keys keep the QR small. The app also accepts the older long keys
(`host`, `port`, `token`, `name`).

`t` is the 8-character pairing code. It is compared case-insensitively and
ignores dashes and spaces, so `k7m4-qx2b` and `K7M4 QX2B` are accepted. Wrong
codes are throttled per address (see [security](security.md)).

## Transport

Endpoint: `ws://<host>:<port>/ws?token=<pairing-code>`.
The code may also be sent as `Authorization: Bearer <code>`.

## Server → client

### `hello` — first message after the handshake

```json
{
  "t": "hello",
  "app": "pocketdesk",
  "version": "0.1.0",
  "codec": "jpeg",
  "fps": 15,
  "width": 0,
  "height": 0,
  "input": true,
  "input_error": null,
  "read_only": false
}
```

`width`/`height` may be `0` until the first frame arrives. `input` is `false` when the host cannot inject events; `input_error` then explains why (worth showing verbatim — it usually says how to fix the permission).

### `size` — the frame dimensions changed

```json
{"t":"size","w":1366,"h":768}
```

Sent before the first frame and whenever the resolution changes.

### `stats` — every 5 seconds

```json
{"t":"stats","fps":19.6,"kbps":16519,"capture_fps":20.0,"clients":1}
```

`fps`/`kbps` are measured per connection; `capture_fps` is the host-wide capture rate.

### `error`

```json
{"t":"error","msg":"host is running in read-only mode"}
```

Non-fatal: the connection stays open.

### Binary

A JPEG image, complete in one WebSocket message (no fragmentation, no deltas). Dimensions come from the image itself; `size` is a convenience.

## Client → server

Coordinates (`x`, `y`) are normalized **inside the frame**, `0.0 … 1.0`, origin top-left.

| Message | Meaning |
| --- | --- |
| `{"t":"move","x":0.52,"y":0.31}` | move pointer to that point |
| `{"t":"click","button":"left","count":1}` | press+release at the current position |
| `{"t":"button","button":"left","down":true}` | press or release without moving (drag support) |
| `{"t":"scroll","dy":1.0,"dx":0.0}` | wheel; positive `dy` = finger moved down = wheel down |
| `{"t":"key","name":"enter","mods":["ctrl"]}` | one key tap with modifiers held |
| `{"t":"key","name":"shift","action":"down"}` | hold a key (`action`: `tap` \| `down` \| `up`) |
| `{"t":"text","s":"hello world"}` | type a string on a US layout, shift handled by the host |
| `{"t":"ping"}` | answered with `{"t":"pong"}` |

Buttons: `left`, `right`, `middle`.

Key names (`key.name`): single names from this set —

```
esc tab enter backspace space delete insert home end pageup pagedown
up down left right capslock printscreen
minus equal comma dot slash semicolon apostrophe grave backslash
leftbrace rightbrace f1 … f12
```

Modifiers (`mods`): `ctrl`, `alt`, `shift`, `super` (also `rctrl`, `ralt`, `rshift`, `rmeta`).

Anything else is rejected with an `error` message; the connection is not closed.

## Example session

```
→ GET /ws?token=8f0c… HTTP/1.1
← 101 Switching Protocols
← {"t":"hello","codec":"jpeg","fps":15,"input":true,…}
← {"t":"size","w":1366,"h":768}
← <binary JPEG>            (one per frame, ~15/s)
→ {"t":"move","x":0.5,"y":0.5}
→ {"t":"click","button":"left"}
← {"t":"stats","fps":14.8,"kbps":12040,"capture_fps":15.0,"clients":1}
```

## Compatibility notes

* The protocol is versioned by the capability fields in `hello`, not by a version number. Clients must ignore unknown fields and unknown message types.
* A future H.264 mode will announce `"codec":"h264"` in `hello`; JPEG-only clients can refuse and keep the current behaviour.
* No message is acknowledged. Input is fire-and-forget; ordering inside one socket is guaranteed by TCP.

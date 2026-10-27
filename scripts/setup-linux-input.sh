#!/usr/bin/env bash
#
# One-time setup so PocketDesk can move your mouse and type on your behalf.
#
#   sudo ./scripts/setup-linux-input.sh
#
# It creates a dedicated group, grants it access to /dev/uinput (the kernel's
# virtual input device), and loads the module at boot. Nothing here is specific
# to PocketDesk: any tool that synthesises input needs the same permission.
#
# Afterwards log out and back in, or run `newgrp pocketdesk-input`.

set -euo pipefail

GROUP="pocketdesk-input"
RULE="/etc/udev/rules.d/99-pocketdesk-uinput.rules"
MODULES="/etc/modules-load.d/pocketdesk.conf"

if [[ ${EUID} -ne 0 ]]; then
    echo "This script needs root. Run: sudo $0" >&2
    exit 1
fi

if [[ -z "${SUDO_USER:-}" ]]; then
    echo "Run it through sudo from your normal account so the right user is added." >&2
    exit 1
fi

echo "→ creating group ${GROUP}"
getent group "${GROUP}" >/dev/null || groupadd "${GROUP}"

echo "→ adding ${SUDO_USER} to ${GROUP}"
usermod -aG "${GROUP}" "${SUDO_USER}"

echo "→ writing ${RULE}"
cat >"${RULE}" <<EOF
# PocketDesk: allow the ${GROUP} group to create virtual input devices.
KERNEL=="uinput", SUBSYSTEM=="misc", GROUP="${GROUP}", MODE="0660", OPTIONS+="static_node=uinput"
EOF

echo "→ loading uinput now and at boot"
echo uinput >"${MODULES}"
modprobe uinput 2>/dev/null || true

echo "→ applying udev rules"
udevadm control --reload-rules
udevadm trigger --name-match=uinput 2>/dev/null || true
# Some kernels do not re-apply ownership on trigger; fix the current device too.
[[ -e /dev/uinput ]] && chgrp "${GROUP}" /dev/uinput && chmod 0660 /dev/uinput

cat <<EOF

Done.

Next:
  1. Log out and back in (or run: newgrp ${GROUP})
  2. Start the host:  pocketdesk
  3. It should now print:  input    enabled (uinput virtual pointer + keyboard)

Screen sharing works even without this step; only remote mouse/keyboard needs it.
EOF

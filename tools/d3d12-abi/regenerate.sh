#!/bin/bash
# Regenerates the Direct3D 12 / DXGI ABI bindings (common/.../d3d12/interop/abi) from the mingw-w64 headers.
# Requires: python3, x86_64-w64-mingw32-gcc (Ubuntu: apt install mingw-w64) and wine64 (to run the measuring probe).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
work="$(mktemp -d)"
python3 -I "$here/gen_abi.py" "$work"
rm -rf "$repo/common/src/main/java/net/caffeinemc/mods/sodium/client/d3d12/interop/abi"
python3 -I "$here/gen_java.py" "$work" "$repo/common/src/main/java" ${1:+--only "$1"}

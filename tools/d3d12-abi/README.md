# Direct3D 12 ABI generator

The Direct3D 12 backend calls `d3d12.dll`, `dxgi.dll` and `d3dcompiler_47.dll` through the Foreign Function & Memory API.
Struct layouts, enum values, COM vtable slots and interface IDs are *not* written by hand: they are measured from the
Windows headers (as shipped by mingw-w64) so that a typo cannot silently corrupt a native call.

* `gen_abi.py` parses the headers, compiles a C probe with `x86_64-w64-mingw32-gcc`, runs it under `wine64`, and writes
  `layouts.json`, `enums.json`, `defines.json`, `vtables.json` and `guids.json`.
* `gen_java.py` turns those tables into `net.caffeinemc.mods.sodium.client.d3d12.interop.abi` (`Iids`, `Vtbl`, `Enums`,
  `Defines`, and one accessor class per struct under `structs`).
* `regenerate.sh [struct-list]` runs both. Pass a file with one C struct name per line to emit only those structs.

The generated sources are committed; they only need regenerating if the set of used interfaces or structs changes.

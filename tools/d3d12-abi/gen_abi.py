#!/usr/bin/env python3
"""Generate ground-truth ABI tables for D3D12 / DXGI / D3DCompiler from the mingw-w64 headers.

Outputs (next to this script):
  (usage: gen_abi.py [output-dir]; needs x86_64-w64-mingw32-gcc and wine64 to run the compiled probe)
  vtables.json / vtables.md   interface -> ordered methods (slot index, return type, name, params) incl. inherited ones
  guids.json                  IID_* / CLSID_* -> "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
  enums.json                  enum type -> {enumerator: value}      (values evaluated by a real C compiler)
  defines.json                numeric #define constants              (evaluated by a real C compiler)
  layouts.json                struct -> {size, align, fields: {name: offset}}   (measured by a real C compiler)
"""
import json, os, re, subprocess, sys, tempfile

HDR = "/usr/x86_64-w64-mingw32/include"
FILES = ["d3dcommon.h", "dxgicommon.h", "dxgiformat.h", "dxgitype.h", "dxgi.h", "dxgi1_2.h", "dxgi1_3.h", "dxgi1_4.h",
         "dxgi1_5.h", "dxgi1_6.h", "d3d12.h", "d3d12sdklayers.h", "d3dcompiler.h", "dxgidebug.h"]
OUT = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
os.makedirs(OUT, exist_ok=True)
CC = "x86_64-w64-mingw32-gcc"


def read(name):
    with open(os.path.join(HDR, name), encoding="utf-8", errors="replace") as f:
        return f.read()


def strip_comments(s):
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    return re.sub(r"//[^\n]*", "", s)


texts = {n: read(n) for n in FILES if os.path.exists(os.path.join(HDR, n))}
all_text = "\n".join(texts.values())
clean = strip_comments(all_text)

# ---------------------------------------------------------------- vtables
vt = {}
for m in re.finditer(r"typedef struct (\w+)Vtbl \{(.*?)\} \1Vtbl;", clean, flags=re.S):
    iface, body = m.group(1), m.group(2).replace("BEGIN_INTERFACE", "").replace("END_INTERFACE", "")
    methods = []
    for mm in re.finditer(r"([A-Za-z_][\w\s\*]*?)\s*\(\s*(?:STDMETHODCALLTYPE|__stdcall)?\s*\*\s*(\w+)\s*\)\s*\((.*?)\)\s*;", body, flags=re.S):
        ret, name, params = mm.group(1).strip(), mm.group(2), " ".join(mm.group(3).split())
        params = re.sub(r"^\w+\s*\*\s*This\s*,?\s*", "", params)
        if params == "This" or params.endswith("* This"):
            params = ""
        methods.append({"slot": len(methods), "ret": ret, "name": name, "params": params})
    vt[iface] = methods
json.dump(vt, open(os.path.join(OUT, "vtables.json"), "w"), indent=1)
with open(os.path.join(OUT, "vtables.md"), "w") as f:
    f.write("# COM vtable slots (from mingw-w64 headers). slot = index in the vtable (IUnknown = 0..2). `This` omitted.\n\n")
    for iface in sorted(vt):
        f.write(f"## {iface}\n")
        for mt in vt[iface]:
            f.write(f"- [{mt['slot']}] `{mt['ret']} {mt['name']}({mt['params']})`\n")
        f.write("\n")

# ---------------------------------------------------------------- GUIDs
guids = {}
for m in re.finditer(r"DEFINE_GUID\(\s*(\w+)\s*,\s*(0x[0-9a-fA-F]+)\s*,\s*(0x[0-9a-fA-F]+)\s*,\s*(0x[0-9a-fA-F]+)\s*,"
                     r"((?:\s*0x[0-9a-fA-F]+\s*,?){8})\s*\)", clean):
    name = m.group(1)
    d1, d2, d3 = int(m.group(2), 16), int(m.group(3), 16), int(m.group(4), 16)
    tail = [int(x, 16) for x in re.findall(r"0x[0-9a-fA-F]+", m.group(5))]
    guids[name] = "%08x-%04x-%04x-%02x%02x-%s" % (d1, d2, d3, tail[0], tail[1], "".join("%02x" % t for t in tail[2:]))
json.dump(guids, open(os.path.join(OUT, "guids.json"), "w"), indent=1, sort_keys=True)

# ---------------------------------------------------------------- enums & defines (evaluated by gcc)
enums = {}
for m in re.finditer(r"typedef enum (?:_?\w+\s*)?\{(.*?)\}\s*(\w+)\s*;", clean, flags=re.S):
    names = []
    for item in m.group(1).split(","):
        item = item.strip()
        if not item:
            continue
        mm = re.match(r"([A-Za-z_]\w*)", item)
        if mm:
            names.append(mm.group(1))
    enums[m.group(2)] = names

defines = []
for m in re.finditer(r"^\s*#\s*define\s+((?:D3D12|DXGI|D3D_|D3DCOMPILE|D3D_COMPILE)\w*)\s+(.+)$", all_text, flags=re.M):
    name, val = m.group(1), strip_comments(m.group(2)).strip()
    if "(" in name or not val or "\"" in val or "." in val.replace("0x", "") and not re.search(r"0x", val):
        continue
    if re.search(r"[A-Za-z_]\w*\s*\(", val) and not val.startswith("("):
        continue
    defines.append(name)
defines = sorted(set(defines))

# ---------------------------------------------------------------- struct fields
struct_bodies = {}


def balanced_structs(text):
    for m in re.finditer(r"typedef struct (?:_?\w+\s*)?\{", text):
        i = m.end()
        depth = 1
        while i < len(text) and depth:
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
            i += 1
        body = text[m.end(): i - 1]
        nm = re.match(r"\s*(\w+)\s*;", text[i:])
        if nm:
            yield nm.group(1), body


for name, body in balanced_structs(clean):
    if name.endswith("Vtbl") or re.match(r"^(ID3D|IDXGI|ID3DBlob)", name):
        continue
    struct_bodies[name] = body


def split_top(body):
    stmts, depth, cur = [], 0, []
    for ch in body:
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
        if ch == ";" and depth == 0:
            stmts.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    return [s for s in stmts if s]


def fields_detail(body, in_union=False):
    """Ordered member records: {name, type, dims, kind} ; anonymous unions/structs are flattened (kind marks union members)."""
    out = []
    for st in split_top(body):
        if "{" in st:
            head = st[:st.index("{")]
            inner = st[st.index("{") + 1: st.rindex("}")]
            tail = st[st.rindex("}") + 1:].strip()
            tm = re.match(r"([A-Za-z_]\w*)", tail)
            is_union = "union" in head
            if tm and not (tm.group(1).startswith("DUMMY") or tm.group(1).startswith("__C89_NAMELESS")):
                dims = [int(x) if x.isdigit() else x for x in re.findall(r"\[\s*(\w+)\s*\]", tail)]
                out.append({"name": tm.group(1), "type": ("union" if is_union else "struct") + " {...}", "dims": dims, "kind": "nested"})
            else:
                for rec in fields_detail(inner, in_union or is_union):
                    if is_union:
                        rec = dict(rec, kind="union")
                    out.append(rec)
            continue
        if ":" in st:
            continue  # bitfield
        st = " ".join(st.replace("__C89_NAMELESS", "").split())
        # "TYPE name[3], other;"  -> split declarators
        mt = re.match(r"((?:const\s+|struct\s+|enum\s+|unsigned\s+|signed\s+)*[A-Za-z_]\w*(?:\s+(?:long|int|char|short))*)\s+(.*)$", st)
        if not mt:
            continue
        base, decls = mt.group(1), mt.group(2)
        for part in decls.split(","):
            part = part.strip()
            mm = re.match(r"((?:\*\s*(?:const\s*)?)*)\s*([A-Za-z_]\w*)\s*((?:\[[^\]]*\]\s*)*)$", part)
            if not mm:
                continue
            ptr = mm.group(1).count("*")
            dims = [int(x) if x.isdigit() else x for x in re.findall(r"\[\s*(\w+)\s*\]", mm.group(3))]
            out.append({"name": mm.group(2), "type": base + (" " + "*" * ptr if ptr else ""), "dims": dims, "kind": "union" if in_union else "plain"})
    return out


def fields_of(body):
    return [r["name"] for r in fields_detail(body)]


layout_src = {}
detail_src = {}
for name, body in struct_bodies.items():
    flds = [f for f in fields_of(body) if f not in ("const", "unsigned")]
    layout_src[name] = flds
    detail_src[name] = fields_detail(body)

# ---------------------------------------------------------------- C probes
def build_probe(structs, enum_map, defs):
    lines = ["#define COBJMACROS", "#define INITGUID", "#include <windows.h>", "#include <stdio.h>", "#include <stddef.h>"]
    for h in FILES:
        if h in texts and h not in ("dxgidebug.h",):
            lines.append(f"#include <{h}>")
    lines.append("#define A(T) _Alignof(T)")
    lines.append("int main(void) {")
    for s, flds in structs.items():
        lines.append(f'  printf("S|{s}|%zu|%zu", sizeof({s}), (size_t)A({s}));')
        for fl in flds:
            lines.append(f'  printf("|{fl}=%zu", offsetof({s}, {fl}));')
        lines.append('  printf("\\n");')
    for e, names in enum_map.items():
        for n in names:
            lines.append(f'  printf("E|{e}|{n}|%lld\\n", (long long)({n}));')
    for d in defs:
        lines.append(f'  printf("D|{d}|%lld\\n", (long long)({d}));')
    lines.append("  return 0; }")
    return lines


def try_compile(structs, enum_map, defs, tmp):
    src = os.path.join(tmp, "probe.c")
    lines = build_probe(structs, enum_map, defs)
    open(src, "w").write("\n".join(lines))
    r = subprocess.run([CC, "-std=gnu11", "-w", "-o", os.path.join(tmp, "probe.exe"), src], capture_output=True, text=True)
    return r, lines


def run_gen():
    structs = dict(layout_src)
    enum_map = {k: v for k, v in enums.items()}
    defs = list(defines)
    with tempfile.TemporaryDirectory() as tmp:
        for attempt in range(60):
            r, lines = try_compile(structs, enum_map, defs, tmp)
            if r.returncode == 0:
                return structs, enum_map, defs, tmp_copy(tmp)
            bad = set()
            for em in re.finditer(r"probe\.c:(\d+):\d+: error", r.stderr):
                bad.add(int(em.group(1)) - 1)
            if not bad:
                sys.stderr.write(r.stderr[:3000])
                raise SystemExit("probe compile failed with unparsable errors")
            for ln in sorted(bad):
                if ln >= len(lines):
                    continue
                text = lines[ln]
                m1 = re.match(r'\s*printf\("S\|(\w+)\|', text)
                m2 = re.match(r'\s*printf\("\|(\w+)=', text)
                m3 = re.match(r'\s*printf\("E\|(\w+)\|(\w+)\|', text)
                m4 = re.match(r'\s*printf\("D\|(\w+)\|', text)
                if m1:
                    structs.pop(m1.group(1), None)
                elif m2:
                    # find owning struct by scanning upwards
                    k = ln
                    while k >= 0 and not re.match(r'\s*printf\("S\|', lines[k]):
                        k -= 1
                    ms = re.match(r'\s*printf\("S\|(\w+)\|', lines[k])
                    if ms and ms.group(1) in structs:
                        structs[ms.group(1)] = [f for f in structs[ms.group(1)] if f != m2.group(1)]
                elif m3:
                    if m3.group(1) in enum_map and m3.group(2) in enum_map[m3.group(1)]:
                        enum_map[m3.group(1)].remove(m3.group(2))
                elif m4:
                    if m4.group(1) in defs:
                        defs.remove(m4.group(1))
        raise SystemExit("too many compile retries")


def tmp_copy(tmp):
    exe = os.path.join(tmp, "probe.exe")
    dst = os.path.join(tempfile.gettempdir(), "d3d12_abi_probe.exe")
    with open(exe, "rb") as a, open(dst, "wb") as b:
        b.write(a.read())
    return dst


structs, enum_map, defs, exe = run_gen()
env = dict(os.environ, WINEDEBUG="-all", PATH="/usr/lib/wine:" + os.environ["PATH"])
env.setdefault("WINEPREFIX", os.path.join(tempfile.gettempdir(), "d3d12_abi_wineprefix"))
res = subprocess.run(["wine64", exe], capture_output=True, text=True, env=env, timeout=300)
layouts, enum_vals, def_vals = {}, {}, {}
for line in res.stdout.splitlines():
    p = line.split("|")
    if p[0] == "S":
        offs = {kv.split("=")[0]: int(kv.split("=")[1]) for kv in p[4:] if "=" in kv}
        layouts[p[1]] = {"size": int(p[2]), "align": int(p[3]), "fields": offs,
                         "decl": [dict(d, offset=offs[d["name"]]) for d in detail_src.get(p[1], []) if d["name"] in offs]}
    elif p[0] == "E":
        enum_vals.setdefault(p[1], {})[p[2]] = int(p[3])
    elif p[0] == "D":
        def_vals[p[1]] = int(p[2])
json.dump(layouts, open(os.path.join(OUT, "layouts.json"), "w"), indent=1, sort_keys=True)
json.dump(enum_vals, open(os.path.join(OUT, "enums.json"), "w"), indent=1, sort_keys=True)
json.dump(def_vals, open(os.path.join(OUT, "defines.json"), "w"), indent=1, sort_keys=True)
print(f"interfaces={len(vt)} guids={len(guids)} structs={len(layouts)} enums={len(enum_vals)} defines={len(def_vals)}")

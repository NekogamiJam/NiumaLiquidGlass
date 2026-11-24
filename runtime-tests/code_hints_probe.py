#!/usr/bin/env python3
"""Portable isolated native regression for Niuma completion/code hints.

The probe copies assets into --temp-dir and never writes the source/assets tree.
Requires a desktop librime DLL (tested with Weasel 0.17.4 / librime 1.17.0).
"""
from __future__ import annotations

import argparse
import ctypes as C
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


class Traits(C.Structure):
    _fields_ = [
        ("data_size", C.c_int),
        ("shared_data_dir", C.c_char_p),
        ("user_data_dir", C.c_char_p),
        ("distribution_name", C.c_char_p),
        ("distribution_code_name", C.c_char_p),
        ("distribution_version", C.c_char_p),
        ("app_name", C.c_char_p),
        ("modules", C.POINTER(C.c_char_p)),
        ("min_log_level", C.c_int),
        ("log_dir", C.c_char_p),
        ("prebuilt_data_dir", C.c_char_p),
        ("staging_dir", C.c_char_p),
    ]


class Candidate(C.Structure):
    _fields_ = [("text", C.c_char_p), ("comment", C.c_char_p), ("reserved", C.c_void_p)]


class Commit(C.Structure):
    _fields_ = [("data_size", C.c_int), ("text", C.c_char_p)]


class Composition(C.Structure):
    _fields_ = [("length", C.c_int), ("cursor_pos", C.c_int), ("sel_start", C.c_int), ("sel_end", C.c_int), ("preedit", C.c_char_p)]


class Menu(C.Structure):
    _fields_ = [("page_size", C.c_int), ("page_no", C.c_int), ("is_last_page", C.c_int), ("highlighted_candidate_index", C.c_int), ("num_candidates", C.c_int), ("candidates", C.POINTER(Candidate)), ("select_keys", C.c_char_p)]


class Context(C.Structure):
    _fields_ = [("data_size", C.c_int), ("composition", Composition), ("menu", Menu), ("commit_text_preview", C.c_char_p), ("select_labels", C.POINTER(C.c_char_p))]


def bind(lib, name, result, *args):
    fn = getattr(lib, name)
    fn.restype = result
    fn.argtypes = list(args)
    return fn


def text(value: bytes | None) -> str:
    return (value or b"").decode("utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dll-dir", type=Path, required=True)
    parser.add_argument("--assets-dir", type=Path, required=True, help="shared asset directory")
    parser.add_argument("--schema", type=Path, required=True, help="pending schema to copy")
    parser.add_argument("--lua", type=Path, required=True, help="pending niuma_fourcode.lua to copy")
    parser.add_argument("--temp-dir", type=Path, help="explicit new work directory to keep for diagnosis")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if args.temp_dir is not None:
        return run(args, args.temp_dir.resolve())
    # The Windows librime logger can hold open files even after RimeFinalize.
    # Run the DLL in a child so the parent cleans up only after it exits.
    with tempfile.TemporaryDirectory(prefix='niuma-code-hints-') as work:
        return subprocess.call([sys.executable, str(Path(__file__).resolve()), *sys.argv[1:],
                                '--temp-dir', str(Path(work) / 'probe')])


def run(args, root: Path) -> int:
    assets = args.assets_dir.resolve()
    report_path = args.report.resolve()
    if root.exists() or root.is_relative_to(assets) or report_path.is_relative_to(assets) or report_path.exists():
        raise SystemExit('Use a new temporary directory and report file outside the shared assets.')
    shared, user, logs = root / "shared", root / "user", root / "logs"
    shutil.copytree(assets, shared)
    (shared / "niuma_glass.schema.yaml").write_bytes(args.schema.resolve().read_bytes())
    lua_target = shared / "lua" / "niuma_fourcode.lua"
    lua_target.parent.mkdir(parents=True, exist_ok=True)
    lua_target.write_bytes(args.lua.resolve().read_bytes())
    user.mkdir(); logs.mkdir()
    (user / "default.yaml").write_bytes((shared / "default.yaml").read_bytes())
    (user / "default.custom.yaml").write_text("patch:\n  schema_list:\n    - schema: niuma_glass\n", encoding="utf-8")

    # Load under the DLL search-path context so dependent librime modules resolve.
    import os
    with os.add_dll_directory(str(args.dll_dir.resolve())):
        rime = C.CDLL(str(args.dll_dir.resolve() / "rime.dll"))
        setup = bind(rime, "RimeSetup", None, C.POINTER(Traits))
        initialize = bind(rime, "RimeInitialize", None, C.POINTER(Traits))
        finalize = bind(rime, "RimeFinalize", None)
        maintenance = bind(rime, "RimeStartMaintenance", C.c_int, C.c_int)
        join = bind(rime, "RimeJoinMaintenanceThread", None)
        create = bind(rime, "RimeCreateSession", C.c_size_t)
        destroy = bind(rime, "RimeDestroySession", C.c_int, C.c_size_t)
        select_schema = bind(rime, "RimeSelectSchema", C.c_int, C.c_size_t, C.c_char_p)
        clear = bind(rime, "RimeClearComposition", None, C.c_size_t)
        process_key = bind(rime, "RimeProcessKey", C.c_int, C.c_size_t, C.c_int, C.c_int)
        select = bind(rime, "RimeSelectCandidate", C.c_int, C.c_size_t, C.c_size_t)
        get_commit = bind(rime, "RimeGetCommit", C.c_int, C.c_size_t, C.POINTER(Commit))
        free_commit = bind(rime, "RimeFreeCommit", None, C.POINTER(Commit))
        get_context = bind(rime, "RimeGetContext", C.c_int, C.c_size_t, C.POINTER(Context))
        free_context = bind(rime, "RimeFreeContext", None, C.POINTER(Context))

        traits = Traits()
        traits.data_size = C.sizeof(Traits) - C.sizeof(C.c_int)
        traits.shared_data_dir = str(shared).encode()
        traits.user_data_dir = str(user).encode()
        traits.distribution_name = b"Niuma code-hints probe"
        traits.distribution_code_name = b"niuma-code-hints-probe"
        traits.distribution_version = b"0.1"
        traits.app_name = b"niuma.code.hints.probe"
        traits.log_dir = str(logs).encode()
        traits.min_log_level = 1
        setup(C.byref(traits)); initialize(C.byref(traits)); maintenance(1); join()
        sid = create()
        if not sid or not select_schema(sid, b"niuma_glass"):
            raise RuntimeError("failed to create/select isolated niuma_glass session")

        def drain() -> str:
            commit = Commit(); commit.data_size = C.sizeof(Commit) - C.sizeof(C.c_int)
            if not get_commit(sid, C.byref(commit)):
                return ""
            try:
                return text(commit.text)
            finally:
                free_commit(C.byref(commit))

        def state() -> dict:
            ctx = Context(); ctx.data_size = C.sizeof(Context) - C.sizeof(C.c_int)
            if not get_context(sid, C.byref(ctx)):
                return {"preedit": "", "raw_length": 0, "candidates": []}
            try:
                items = []
                for i in range(ctx.menu.num_candidates):
                    candidate = ctx.menu.candidates[i]
                    items.append({"text": text(candidate.text), "comment": text(candidate.comment)})
                return {"preedit": text(ctx.composition.preedit), "raw_length": ctx.composition.length, "candidates": items}
            finally:
                free_context(C.byref(ctx))

        def feed(code: str) -> dict:
            clear(sid); commits = []
            for char in code:
                process_key(sid, ord(char), 0)
                if (value := drain()):
                    commits.append(value)
            return {"commits": commits, "state": state()}

        def choose(code: str, index: int) -> dict:
            feed(code)
            ok = bool(select(sid, index))
            return {"ok": ok, "commit": drain(), "state": state()}

        results = {
            "zt_top3": feed("zt")["state"]["candidates"][:3],
            "zt_select_1": choose("zt", 1),
            "zt_select_2": choose("zt", 2),
            "partial_word_select_a3": choose("a", 3),
            "zaps_then_l": feed("zapsl"),
            "zaps_select_0": choose("zaps", 0),
            "zjia_then_n": feed("zjian"),
            "aaaa_period": feed("aaaa."),
        }
        destroy(sid); finalize()

    report = {
        "probe": "niuma-code-hints",
        "engine": "desktop librime via explicit rime.dll",
        "schema_sha256": hashlib.sha256(args.schema.resolve().read_bytes()).hexdigest(),
        "lua_sha256": hashlib.sha256(args.lua.resolve().read_bytes()).hexdigest(),
        "isolated_root": str(root),
        "results": results,
    }
    def selected(case: str, expected: str) -> bool:
        result = results[case]
        return result['ok'] and result['commit'] == expected and result['state']['preedit'] == ''

    checks = {
        'zt exact first and visible code suffixes': results['zt_top3'] == [
            {'text': '“', 'comment': ''}, {'text': '⊙', 'comment': 'h'}, {'text': '☯', 'comment': 'j'},
        ],
        'zt second-select': selected('zt_select_1', '⊙'),
        'zt third-select': selected('zt_select_2', '☯'),
        'partial word select': selected('partial_word_select_a3', '式'),
        'four-letter prefix without exact match can extend': results['zaps_then_l']['commits'] == []
        and results['zaps_then_l']['state']['preedit'] == 'zapsl'
        and results['zaps_then_l']['state']['candidates'][0:1] == [{'text': 'υ', 'comment': ''}],
        'four-letter prefix without exact match can be selected': selected('zaps_select_0', 'υ'),
        'zjia rollover beats zjian': results['zjia_then_n']['commits'] == ['＋']
        and results['zjia_then_n']['state']['preedit'] == 'n',
        'period still commits': results['aaaa_period']['commits'] == ['恭恭敬敬。']
        and results['aaaa_period']['state']['preedit'] == '',
    }
    report['checks'] = checks
    with report_path.open('x', encoding='utf-8') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print(json.dumps(checks, ensure_ascii=False, indent=2))
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())

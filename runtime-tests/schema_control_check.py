"""A/B control: are the 63 desktop-regression mismatches caused by the schema
key_binder change, or by the environment (theme/dict deploy)?

Runs the same candidate-accuracy regression as desktop_rime_check.py --android-assets
on the identical code subset, twice, in fresh isolated directories:
  A: the pre-change schema (reconstructed byte-exactly, hash 612b...)
  B: the current staged schema (the period-fix revision)
Same staged dictionaries, theme and Lua in both. No ADB, no shared-asset writes.
"""
from __future__ import annotations
import ctypes as C
import hashlib
import json
import os
import shutil
import time
from collections import defaultdict
from pathlib import Path
from isolated_work import isolated_work

PROJECT = Path(__file__).resolve().parents[1]
INPUT = PROJECT / 'input-data'
STAMP = time.strftime('%Y%m%d-%H%M%S')
DLL_DIR = Path(os.environ.get('NIUMA_RIME_DLL_DIR') or '')
if not DLL_DIR.is_dir():
    raise SystemExit('set NIUMA_RIME_DLL_DIR to a Rime install directory that contains librime.dll')
STAGED = PROJECT / 'app/src/main/assets/shared'
OLD_SCHEMA = Path(__file__).with_name('old_schema_612b_reconstructed.yaml')
assert hashlib.sha256(OLD_SCHEMA.read_bytes()).hexdigest() == \
    '612b109d5e04b9b19c1f4595b181e040c2a49d0073e7f75220a01aeea2da1380'


def h(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def parse_rows(p):
    lines = p.read_text(encoding='utf-8-sig').splitlines()
    end = next(i for i, s in enumerate(lines) if s.strip() == '...')
    columns = []
    in_columns = False
    for s in lines[:end]:
        if s.lstrip().startswith('#'):
            continue
        if s.strip() == 'columns:':
            in_columns = True
            continue
        if in_columns:
            if s.strip().startswith('- '):
                columns.append(s.strip()[2:].split('#', 1)[0].strip())
            elif s and not s[0].isspace():
                in_columns = False
    for line in lines[end + 1:]:
        if not line or line.startswith('#'):
            continue
        fields = line.split('\t')
        if len(fields) <= max(columns.index('code'), columns.index('text')):
            raise ValueError('Malformed dictionary row')
        yield fields[columns.index('code')], fields[columns.index('text')]


expected = defaultdict(list)
for name in ('niumawb_zc.dict.yaml', 'niumawb_fh.dict.yaml'):
    for code, text in parse_rows(INPUT / name):
        if text not in expected[code]:
            expected[code].append(text)
codes = sorted(expected)
# Identical subset as desktop_rime_check.py's default (no --all).
import collections
chosen = set(c for c in codes if len(c) in (1, 5))
counts = collections.Counter()
for c in codes:
    counts[c] = len(expected[c])
chosen.update(sorted(codes, key=lambda c: (-len(expected[c]), c))[:80])
step = max(1, len(codes) // 120)
chosen.update(codes[::step])


class Traits(C.Structure):
    _fields_ = [('data_size', C.c_int), ('shared_data_dir', C.c_char_p), ('user_data_dir', C.c_char_p),
                ('distribution_name', C.c_char_p), ('distribution_code_name', C.c_char_p),
                ('distribution_version', C.c_char_p), ('app_name', C.c_char_p), ('modules', C.POINTER(C.c_char_p)),
                ('min_log_level', C.c_int), ('log_dir', C.c_char_p), ('prebuilt_data_dir', C.c_char_p),
                ('staging_dir', C.c_char_p)]


class Candidate(C.Structure):
    _fields_ = [('text', C.c_char_p), ('comment', C.c_char_p), ('reserved', C.c_void_p)]


class CandidateIterator(C.Structure):
    _fields_ = [('ptr', C.c_void_p), ('index', C.c_int), ('candidate', Candidate)]


def run_variant(label: str, work: Path, schema_override: Path | None):
    shared = work / 'shared'
    user = work / 'user'
    log = work / 'logs'
    for d in (shared, user, log):
        d.mkdir(parents=True, exist_ok=True)
    shutil.copytree(STAGED, shared, dirs_exist_ok=True)
    if schema_override is not None:
        (shared / 'niuma_glass.schema.yaml').write_bytes(schema_override.read_bytes())
    (user / 'default.yaml').write_bytes((shared / 'default.yaml').read_bytes())
    (user / 'default.custom.yaml').write_text('patch:\n  schema_list:\n    - schema: niuma_glass\n',
                                              encoding='utf-8')
    with os.add_dll_directory(str(DLL_DIR)):
        r = C.CDLL(str(DLL_DIR / 'rime.dll'))

        def fn(name, result, *args):
            f = getattr(r, name)
            f.restype = result
            f.argtypes = list(args)
            return f

        setup = fn('RimeSetup', None, C.POINTER(Traits))
        initialize = fn('RimeInitialize', None, C.POINTER(Traits))
        finalize = fn('RimeFinalize', None)
        maintenance = fn('RimeStartMaintenance', C.c_int, C.c_int)
        join = fn('RimeJoinMaintenanceThread', None)
        create = fn('RimeCreateSession', C.c_size_t)
        destroy = fn('RimeDestroySession', C.c_int, C.c_size_t)
        select_schema = fn('RimeSelectSchema', C.c_int, C.c_size_t, C.c_char_p)
        clear = fn('RimeClearComposition', None, C.c_size_t)
        key = fn('RimeProcessKey', C.c_int, C.c_size_t, C.c_int, C.c_int)
        begin = fn('RimeCandidateListBegin', C.c_int, C.c_size_t, C.POINTER(CandidateIterator))
        nxt = fn('RimeCandidateListNext', C.c_int, C.POINTER(CandidateIterator))
        end = fn('RimeCandidateListEnd', None, C.POINTER(CandidateIterator))
        deploy_config = fn('RimeDeployConfigFile', C.c_int, C.c_char_p, C.c_char_p)
        t = Traits()
        t.data_size = C.sizeof(Traits) - C.sizeof(C.c_int)
        t.shared_data_dir = str(shared).encode()
        t.user_data_dir = str(user).encode()
        t.distribution_name = b'Isolated Niuma control'
        t.distribution_code_name = b'niuma-control'
        t.distribution_version = b'0.1'
        t.app_name = b'rime.niuma.control-test'
        t.log_dir = str(log).encode()
        t.min_log_level = 1
        sid = 0
        setup(C.byref(t))
        initialize(C.byref(t))
        try:
            maintenance(1)
            join()
            assert deploy_config(b'niuma_glass.trime.yaml', b'config_version')
            sid = create()
            assert select_schema(sid, b'niuma_glass')

            def candidates(code):
                clear(sid)
                for c in code:
                    key(sid, ord(c), 0)
                it = CandidateIterator()
                items = []
                if begin(sid, C.byref(it)):
                    try:
                        while nxt(C.byref(it)):
                            items.append(it.candidate.text.decode('utf-8'))
                    finally:
                        end(C.byref(it))
                return items

            mismatches = []
            for code in sorted(chosen):
                actual = candidates(code)
                if actual != expected[code]:
                    mismatches.append(dict(code=code, expected=expected[code][:25], actual=actual[:25]))
            return mismatches
        finally:
            if sid:
                destroy(sid)
            finalize()


import sys

VARIANT = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] in ('A', 'B') else 'B'
WORK_ROOT = isolated_work(__file__, 'niuma-schema-control-')
work = WORK_ROOT / f'schema-control-{VARIANT}'
mismatches = run_variant(VARIANT, work, OLD_SCHEMA if VARIANT == 'A' else None)
report = dict(
    variant=VARIANT,
    stamp=STAMP,
    note='A = pre-change schema 612b...; B = current staged schema; same dictionaries/theme/Lua subset regression',
    mismatches=len(mismatches),
    codes=[m['code'] for m in mismatches],
    detail=mismatches,
)
out = PROJECT / 'runtime-tests' / f'schema-control-{VARIANT}-{STAMP}.json'
out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f"variant {VARIANT}: mismatches={len(mismatches)}")
print('report:', out)

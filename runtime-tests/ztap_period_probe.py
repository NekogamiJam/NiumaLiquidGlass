"""Actual desktop librime probe for the two 2026-11-18 Niuma behavior changes.

Scope (engine side only; the Kotlin Z-tap gesture layer and the Android UI are
separate):
  1. '.' with valid code letters + candidates must commit the top candidate
     together with the period punctuation, exactly like ',' already did.
     Before the fix the preset key_binder binding
     { when: has_menu, accept: period, send: Page_Down } swallowed '.'
     whenever a candidate menu was open, so '.' did nothing. The pinned
     first-variant schema (see PINNED_SCHEMA_SHA256 below) redeclares the
     preset binding list without that period item, in every candidate state
     (per the 2026-11-18 lead decision, `when: paging` was rejected because
     it would still intercept paged menus).
  2. Engine-side support of the Z-tap third-candidate change: the third
     candidate (global index 2) commits through RimeSelectCandidate(2)
     exactly like the agreed upswipe-Z third-select; without a third
     candidate the fallback types 'z' (Lua four-code top-commit + new code);
     all punctuation / digit / five-code / fourth-code behaviors preserved.

Modes:
  default: runs against the staged shared assets and refuses to start unless
  their niuma_glass.schema.yaml is the pinned first-variant bytes.
  --standalone: builds an ephemeral shared dir from the staged assets plus
  the pinned first-variant schema read from custom-data, so the probe can be
  green before a restage happens.

This is a desktop-engine test in a fresh isolated data directory; it never
invokes ADB and is NOT proof about the Android JNI build or the UI.
ABI reference: https://raw.githubusercontent.com/rime/librime/1.17.0/src/rime_api.h
"""
from __future__ import annotations
import ctypes as C
import hashlib
import json
import os
import shutil
import sys
import time
from pathlib import Path
from isolated_work import isolated_work

PROJECT = Path(__file__).resolve().parents[1]
INPUT = PROJECT / 'input-data'
STAMP = time.strftime('%Y%m%d-%H%M%S')
WORK = isolated_work(__file__, 'niuma-ztap-period-')
USER = WORK / 'user'
SHARED = WORK / 'shared'
LOG = WORK / 'logs'
for d in (USER, SHARED, LOG):
    d.mkdir(parents=True, exist_ok=False)
DLL_DIR = Path(os.environ.get('NIUMA_RIME_DLL_DIR') or '')
if not DLL_DIR.is_dir():
    raise SystemExit('set NIUMA_RIME_DLL_DIR to a Rime install directory that contains librime.dll')
STAGED_ASSETS = PROJECT / 'app/src/main/assets/shared'
CUSTOM_DATA = PROJECT / 'custom-data'
SCHEMA_ID = b'niuma_glass'
# Pinned first-variant schema bytes: the explicit key_binder list WITHOUT the
# period item (APK-build-staged bytes, hash recorded in ztap-period-20261118-193237.json).
PINNED_SCHEMA_SHA256 = 'c3ec9c962f8677e7decba5805791fac8f027dcf42b7e56759caf4e44858c2105'
STANDALONE = '--standalone' in sys.argv


def h(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


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


class Commit(C.Structure):
    _fields_ = [('data_size', C.c_int), ('text', C.c_char_p)]


class Composition(C.Structure):
    _fields_ = [('length', C.c_int), ('cursor_pos', C.c_int), ('sel_start', C.c_int), ('sel_end', C.c_int),
                ('preedit', C.c_char_p)]


class Menu(C.Structure):
    _fields_ = [('page_size', C.c_int), ('page_no', C.c_int), ('is_last_page', C.c_int),
                ('highlighted_candidate_index', C.c_int), ('num_candidates', C.c_int),
                ('candidates', C.POINTER(Candidate)), ('select_keys', C.c_char_p)]


class Context(C.Structure):
    _fields_ = [('data_size', C.c_int), ('composition', Composition), ('menu', Menu),
                ('commit_text_preview', C.c_char_p), ('select_labels', C.POINTER(C.c_char_p))]


def source_hashes():
    return {p.name: h(p) for p in sorted(INPUT.glob('*.yaml'))}


# Step grammar: one-char string = keypress (ord; '\b' = BackSpace), '\x01' =
# RimeSelectCandidate(select, global) fires at that position. An unhandled
# key is modeled as frontend passthrough (the editor receives the raw char),
# matching the punctuation-check baseline of 2026-11-13.
CASES = [
    # --- the fix: '.' with valid letters + candidates commits top + period ---
    dict(name='aaaa-comma', sequence='aaaa,', options={}, expected='恭恭敬敬，', preedit=''),
    dict(name='aaaa-period', sequence='aaaa.', options={}, expected='恭恭敬敬。', preedit=''),
    dict(name='aaaa-period-then-z', sequence='aaaa.z', options={}, expected='恭恭敬敬。', preedit='z'),
    # Paged state under the pinned first-variant schema: '=' paged the
    # 52-candidate menu (highlight moves to candidate index 9 = '⺄'), then
    # '.' has no binding in any candidate state, so it commits the paged
    # (highlighted) candidate together with the period punctuation.
    dict(name='nnll-period-paged', sequence='nnll=.', options={}, expected='⺄。', preedit=''),
    dict(name='aaab-period', sequence='aaab.', options={}, expected='㐂。', preedit=''),
    # --- Z-tap engine side: third-candidate select + fallback paths ---
    # rime_select_candidate(2) commits the third candidate immediately,
    # exactly like the agreed upswipe-Z third-select.
    dict(name='aaaa-select2-commits', sequence='aaaa\x01', options={},
         expected='㠭', preedit='', select=2),
    # After the third-candidate commit, typing 'z' starts a fresh fh code
    # (same follow-up as the pre-fix Z-tap rollover, minus the committed top).
    dict(name='aaaa-select2-then-z', sequence='aaaa\x01z', options={},
         expected='㠭', preedit='z', select=2),
    dict(name='aaab-z-fallback', sequence='aaabz', options={}, expected='㐂', preedit='z'),
    # --- preserved punctuation / digit states ---
    dict(name='sentence-idle', sequence='.', options={}, expected='。', preedit=''),
    dict(name='chinese-comma', sequence=',', options={}, expected='，', preedit=''),
    dict(name='decimal-dot', sequence='3.', options={}, expected='3.', preedit=''),
    dict(name='decimal-number', sequence='3.14', options={}, expected='3.14', preedit=''),
    dict(name='decimal-space', sequence='3. ', options={}, expected='3. ', preedit=''),
    dict(name='decimal-backspace', sequence='3.\b', options={}, expected='3', preedit=''),
    dict(name='version', sequence='1.2.3', options={}, expected='1.2.3', preedit=''),
    dict(name='colon-separator', sequence='12:', options={}, expected='12:', preedit=''),
    dict(name='comma-separator', sequence='1,', options={}, expected='1，', preedit=''),
    dict(name='semicolon-sentence', sequence=';', options={}, expected='；', preedit=''),
    dict(name='fullshape-decimal', sequence='3.', options={'full_shape': 1}, expected='３．', preedit=''),
    dict(name='ascii-punct-period', sequence='.', options={'ascii_punct': 1}, expected='.', preedit=''),
    dict(name='ascii-decimal', sequence='3.', options={'ascii_mode': 1}, expected='3.', preedit=''),
    # --- preserved four/five-code behaviors ---
    dict(name='fh-five-code', sequence='zjian ', options={}, expected='－', preedit=''),
    dict(name='fourcode-rollover', sequence='aaaaa ', options={}, expected='恭恭敬敬工', preedit=''),
    dict(name='sentence-after-unique4', sequence='khlg.', options={}, expected='中国。', preedit=''),
]


def main() -> int:
    hashes = source_hashes()
    for name in ('niumawb.dict.yaml', 'niumawb_zc.dict.yaml', 'niumawb_fh.dict.yaml'):
        if h(STAGED_ASSETS / name) != hashes[name]:
            raise ValueError(f'Staged Android dictionary differs from the original snapshot: {name}')
    shutil.copytree(STAGED_ASSETS, SHARED, dirs_exist_ok=True)
    staged_schema_hash = h(STAGED_ASSETS / 'niuma_glass.schema.yaml')
    if STANDALONE:
        # Ephemeral v1 verification: staged assets + pinned first-variant schema
        # from custom-data, without touching the staged shared directory.
        custom_schema = CUSTOM_DATA / 'niuma_glass.schema.yaml'
        if h(custom_schema) != PINNED_SCHEMA_SHA256:
            raise ValueError(
                f'custom-data schema is not the pinned first variant: {h(custom_schema)}')
        (SHARED / 'niuma_glass.schema.yaml').write_bytes(custom_schema.read_bytes())
    elif staged_schema_hash != PINNED_SCHEMA_SHA256:
        raise SystemExit(
            f'staged niuma_glass.schema.yaml ({staged_schema_hash[:12]}...) is not the pinned '
            f'first-variant bytes ({PINNED_SCHEMA_SHA256[:12]}...); restage custom-data '
            f'(python stage-custom-data.py) and rerun, or verify now with --standalone')
    (USER / 'default.yaml').write_bytes((SHARED / 'default.yaml').read_bytes())
    (USER / 'default.custom.yaml').write_text('patch:\n  schema_list:\n    - schema: niuma_glass\n',
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
        select = fn('RimeSelectCandidate', C.c_int, C.c_size_t, C.c_size_t)
        get_commit = fn('RimeGetCommit', C.c_int, C.c_size_t, C.POINTER(Commit))
        free_commit = fn('RimeFreeCommit', None, C.POINTER(Commit))
        deploy_config = fn('RimeDeployConfigFile', C.c_int, C.c_char_p, C.c_char_p)
        context = fn('RimeGetContext', C.c_int, C.c_size_t, C.POINTER(Context))
        free_context = fn('RimeFreeContext', None, C.POINTER(Context))
        option = fn('RimeSetOption', None, C.c_size_t, C.c_char_p, C.c_int)

        t = Traits()
        t.data_size = C.sizeof(Traits) - C.sizeof(C.c_int)
        t.shared_data_dir = str(SHARED).encode()
        t.user_data_dir = str(USER).encode()
        t.distribution_name = b'Isolated Niuma test'
        t.distribution_code_name = b'niuma-test'
        t.distribution_version = b'0.1'
        t.app_name = b'rime.niuma.offline-test'
        t.log_dir = str(LOG).encode()
        t.min_log_level = 1
        sid = 0
        setup(C.byref(t))
        initialize(C.byref(t))
        try:
            maintenance(1)
            join()
            theme_deployed = bool(deploy_config(b'niuma_glass.trime.yaml', b'config_version'))
            if not theme_deployed:
                raise RuntimeError('Actual Android theme YAML failed native deployment')
            sid = create()
            if not sid or not select_schema(sid, SCHEMA_ID):
                raise RuntimeError('Isolated schema deployment/session failed')

            def state():
                c = Context()
                c.data_size = C.sizeof(Context) - C.sizeof(C.c_int)
                assert context(sid, C.byref(c))
                try:
                    preedit = (c.composition.preedit or b'').decode()
                    menu = [c.menu.candidates[i].text.decode() for i in range(c.menu.num_candidates)]
                    return preedit, menu, c.menu.highlighted_candidate_index
                finally:
                    free_context(C.byref(c))

            def drain():
                c = Commit()
                c.data_size = C.sizeof(Commit) - C.sizeof(C.c_int)
                if not get_commit(sid, C.byref(c)):
                    return ''
                try:
                    return (c.text or b'').decode()
                finally:
                    free_commit(C.byref(c))

            results = []
            for case in CASES:
                destroy(sid)
                sid = create()
                assert select_schema(sid, SCHEMA_ID)
                for opt_name, value in case.get('options', {}).items():
                    option(sid, opt_name.encode(), value)
                clear(sid)
                editor = ''
                steps = []
                sequence = case['sequence']
                select_index = case.get('select')
                select_fired = False
                for ch in sequence:
                    if ch == '\x01':  # marker: fire the global selection here
                        if select_index is not None and not select_fired:
                            ok = bool(select(sid, select_index, 1))
                            select_fired = True
                            preedit, menu, highlight = state()
                            steps.append(dict(action=f'select_global:{select_index}', handled=ok,
                                              preedit=preedit, menu=menu, highlight=highlight))
                            editor += drain()
                            continue
                        ch = ''
                    if not ch:
                        continue
                    sym = 0xff08 if ch == '\b' else ord(ch)
                    handled = bool(key(sid, sym, 0))
                    commit = drain()
                    editor += commit
                    # Unhandled keys are modeled as frontend passthrough,
                    # matching the 2026-11-13 punctuation-check baseline.
                    if not handled:
                        editor = editor[:-1] if ch == '\b' else editor + ch
                    preedit, menu, highlight = state()
                    steps.append(dict(key='BackSpace' if ch == '\b' else ch, handled=handled,
                                      commit=commit, preedit=preedit, menu=menu, highlight=highlight))
                preedit, menu, highlight = state()
                got = dict(editor=editor, preedit=preedit, menu=menu, highlight=highlight)
                passed = editor == case['expected'] and preedit == case['preedit']
                if case.get('select_highlight') is not None:
                    passed = passed and highlight == case['select_highlight']
                if case.get('paged_expect'):
                    # a paged candidate menu must survive (paging, not commit)
                    passed = passed and bool(menu)
                results.append(dict(name=case['name'], sequence=case['sequence'].replace('\x01', 'SELECT'),
                                    options=case.get('options', {}), expected=case['expected'],
                                    expected_preedit=case['preedit'], got=got, passed=passed))
            report = dict(
                probe='ztap-period', stamp=STAMP,
                mode='standalone-ephemeral-v1' if STANDALONE else 'staged-shared',
                pinned_schema_sha256=PINNED_SCHEMA_SHA256,
                engine='Installed desktop librime 1.17.0 (weasel 0.17.4 rime.dll); no Android or active Windows IME access',
                work=str(WORK),
                schema_sha256=h(SHARED / 'niuma_glass.schema.yaml'),
                theme_sha256=h(SHARED / 'niuma_glass.trime.yaml'),
                lua_sha256=h(SHARED / 'lua/niuma_fourcode.lua'),
                source_hashes=hashes,
                sources_unchanged=hashes == source_hashes(),
                theme_native_deployment_succeeded=theme_deployed,
                cases=results,
                passed=sum(x['passed'] for x in results),
                total=len(results),
                limitations=['Desktop engine check only; the Kotlin Z-tap gesture layer and device UI need their own validation',
                            'Digits unhandled by librime are modeled as frontend passthrough'])
            out = PROJECT / 'runtime-tests' / f'ztap-period-{STAMP}.json'
            out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            print(json.dumps({k: v for k, v in report.items() if k not in ('cases',)}, ensure_ascii=False, indent=2))
            for row in results:
                print(f"  {'PASS' if row['passed'] else 'FAIL'} {row['name']}: {json.dumps(row['got'], ensure_ascii=False)}")
            print('report:', out)
            if '--require-pass' in sys.argv and not all(x['passed'] for x in results):
                raise SystemExit(1)
            return 0
        finally:
            if sid:
                destroy(sid)
            finalize()


if __name__ == '__main__':
    sys.exit(main())

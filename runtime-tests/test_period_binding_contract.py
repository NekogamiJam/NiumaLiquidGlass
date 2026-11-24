"""Stdlib-only contracts for the 2026-11-18 period/z-tap production change.

Checks (no engine, no ADB, no Gradle):
  1. The staged and source niuma_glass.schema.yaml are byte-identical.
  2. Its key_binder binding list equals the preset default binding set minus
     exactly one item: the preset's
     { when: has_menu, accept: period, send: Page_Down } is removed, so '.'
     reaches the punctuator in every candidate state (top candidate commits
     with the period punctuation, like ','). Everything else (emacs
     editing, tab word-move, minus/equal paging, comma paging, numbered mode
     switch) is kept verbatim, and no preset group is imported anymore
     (key_binder has no import_preset).
  3. The input-data dictionaries are still the pinned, unmodified sources.
  4. When a ztap-period probe report exists, every recorded case passed.
"""
from __future__ import annotations
import hashlib
import json
import re
import unittest
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHARED = ROOT / 'app/src/main/assets/shared'
SOURCE_SHA256 = {
    # Pinned by gesture-tools/build_z_transitions.py (same constants).
    'niumawb.dict.yaml': '294e03d15a30f9a53ff78d7b9a5a0060f1dc71a2600ace0091d2765ed6ace92e',
    'niumawb_zc.dict.yaml': '09eff608e8831aa704005aa50ef69450d1729d752e3c212edcc894db9f51aaa9',
    'niumawb_fh.dict.yaml': '05b9981ba4aa20153012bc7762b137a0a29ed8bb8de7c17f2843a079a3466e2b',
}
GROUPS = ('emacs_editing', 'move_by_word_with_tab', 'paging_with_minus_equal',
         'paging_with_comma_period', 'numbered_mode_switch')
# Pinned first-variant schema bytes (lead decision 2026-11-18; shipped in the
# first APK build, hash recorded in ztap-period-20261118-193237.json).
PINNED_SCHEMA_SHA256 = 'd4027dc67bd4f9b47947c726ee63f1a65cd31cca550d426cd52df78f37893745'
BINDING_LINE = re.compile(r'^\s*- \{(.*\}\s*)$')
TOP_KEY = re.compile(r'^[A-Za-z_][\w]*:')


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    digest.update(path.read_bytes())
    return digest.hexdigest()


def parse_bindings(lines):
    """Parse flow-map binding lines into normalized 'when/accept/action' tuples."""
    out = []
    for line in lines:
        m = BINDING_LINE.match(line)
        if not m:
            continue
        body = m.group(1)
        assert body.endswith('}')
        body = body[:-1].strip()
        pairs = [p.strip() for p in body.split(',')]
        kv = {}
        for pair in pairs:
            key, value = pair.split(':', 1)
            kv[key.strip()] = value.strip()
        assert {'when', 'accept'} <= set(kv) and not (set(kv) - {'when', 'accept', 'send', 'toggle', 'select'}), line
        assert sum(1 for k in ('send', 'toggle', 'select') if k in kv) == 1, line
        action = kv.get('send') or kv.get('toggle') or kv.get('select')
        out.append((kv['when'], kv['accept'], action))
    return out


def binding_lines_in_schema(text: str) -> list[str]:
    lines = text.splitlines()
    inside = False
    out = []
    for line in lines:
        if line.startswith('key_binder:'):
            inside = True
            continue
        if inside and TOP_KEY.match(line):
            break
        if inside:
            out.append(line)
    return out


def preset_binding_lines() -> list[str]:
    text = (SHARED / 'key_bindings.yaml').read_text(encoding='utf-8')
    groups: dict[str, list[str]] = {}
    current = None
    for line in text.splitlines():
        if TOP_KEY.match(line):
            current = line.split(':', 1)[0].strip()
            groups.setdefault(current, [])
            continue
        if current in GROUPS:
            groups[current].append(line)
    lines = []
    for group in GROUPS:  # order = preset patch order
        lines.extend(groups[group])
    return lines


class PeriodBindingContractTest(unittest.TestCase):
    def test_staged_schema_matches_source_and_pin(self):
        source = ROOT / 'custom-data/niuma_glass.schema.yaml'
        staged = SHARED / 'niuma_glass.schema.yaml'
        self.assertTrue(source.is_file() and staged.is_file())
        self.assertEqual(sha256(source), sha256(staged), 'staged schema differs from custom-data')
        # The lead-decided first-variant bytes (no period binding at all), as
        # shipped in the first APK build.
        self.assertEqual(sha256(staged), PINNED_SCHEMA_SHA256,
                         'staged schema is not the pinned first-variant bytes; '
                         'run python stage-custom-data.py after the custom-data restore')

    def test_schema_binding_list_is_preset_without_period_item(self):
        staged = (SHARED / 'niuma_glass.schema.yaml').read_text(encoding='utf-8')
        lines = binding_lines_in_schema(staged)
        schema_bindings = parse_bindings(lines)
        # No import_preset under key_binder: the explicit list replaces the preset.
        kb_section = '\n'.join(lines)
        self.assertNotIn('import_preset', kb_section, 'key_binder must not import the preset anymore')
        preset = parse_bindings(preset_binding_lines())
        self.assertEqual(len(preset), 31)
        expected = [b for b in preset if b != ('has_menu', 'period', 'Page_Down')]
        self.assertEqual(schema_bindings, expected,
                         'schema key_binder list must be the preset list minus the '
                         '{when: has_menu, accept: period, send: Page_Down} item')
        self.assertEqual(Counter(schema_bindings), Counter(expected))
        # sanity: comma paging and minus/equal paging survive; no period binding.
        self.assertIn(('paging', 'comma', 'Page_Up'), schema_bindings)
        self.assertIn(('has_menu', 'minus', 'Page_Up'), schema_bindings)
        self.assertIn(('has_menu', 'equal', 'Page_Down'), schema_bindings)
        self.assertNotIn(('has_menu', 'period', 'Page_Down'), schema_bindings)
        self.assertNotIn(('paging', 'period', 'Page_Down'), schema_bindings)
        self.assertEqual(len(schema_bindings), 30)

    def test_other_schema_sections_untouched(self):
        staged = (SHARED / 'niuma_glass.schema.yaml').read_text(encoding='utf-8')
        self.assertIn('lua_processor@*niuma_fourcode', staged)
        self.assertIn('digit_separator_action: commit', staged)
        self.assertIn("auto_select_pattern: '^[a-y][a-z]{3}$'", staged)
        self.assertIn('import_preset: default', staged)  # punctuator and recognizer keep the preset
        self.assertEqual(staged.count('import_preset'), 2)  # punctuator + recognizer only

    def test_input_data_sources_unmodified(self):
        for name, digest in SOURCE_SHA256.items():
            path = ROOT / 'input-data' / name
            self.assertEqual(sha256(path), digest, f'{name} is not the pinned source')
        for name in SOURCE_SHA256:
            self.assertEqual(sha256(SHARED / name), SOURCE_SHA256[name],
                             f'staged {name} differs from the pinned source')

    def test_probe_reports_all_pass_when_present(self):
        reports = sorted((ROOT / 'runtime-tests').glob('ztap-period-*.json'))
        if not reports:
            self.skipTest('no ztap-period probe report yet; run runtime-tests/ztap_period_probe.py')
        # Only the latest report is a contract: older reports are historical
        # evidence for earlier schema revisions.
        report = reports[-1]
        payload = json.loads(report.read_text(encoding='utf-8'))
        self.assertTrue(payload['sources_unchanged'], report.name)
        self.assertTrue(payload['theme_native_deployment_succeeded'], report.name)
        self.assertEqual(payload['schema_sha256'], PINNED_SCHEMA_SHA256,
                         'latest probe report was not produced against the pinned '
                         'first-variant schema bytes')
        self.assertEqual(payload['passed'], payload['total'],
                         f'unpassed cases in {report.name}: '
                         f"{[c['name'] for c in payload['cases'] if not c['passed']]}")


if __name__ == '__main__':
    unittest.main(verbosity=2)

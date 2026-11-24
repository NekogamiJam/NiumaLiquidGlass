"""Static guards for the current code-hint schema; native lookup is tested separately."""
import hashlib
from pathlib import Path
import unittest

PROJECT = Path(__file__).resolve().parents[1]
CUSTOM = PROJECT / 'custom-data'
SHARED = PROJECT / 'app/src/main/assets/shared'
DICTIONARIES = {
    'niumawb.dict.yaml': '294e03d15a30f9a53ff78d7b9a5a0060f1dc71a2600ace0091d2765ed6ace92e',
    'niumawb_zc.dict.yaml': '09eff608e8831aa704005aa50ef69450d1729d752e3c212edcc894db9f51aaa9',
    'niumawb_fh.dict.yaml': '05b9981ba4aa20153012bc7762b137a0a29ed8bb8de7c17f2843a079a3466e2b',
}


class CodeHintsContracts(unittest.TestCase):
    def test_config_copies_match_staged_assets(self):
        for name in ('niuma_glass.schema.yaml', 'lua/niuma_fourcode.lua'):
            self.assertEqual((CUSTOM / name).read_bytes(), (SHARED / name).read_bytes(), name)

    def test_both_dictionaries_remain_untouched(self):
        for name, expected in DICTIONARIES.items():
            for base in (PROJECT / 'input-data', SHARED):
                self.assertEqual(hashlib.sha256((base / name).read_bytes()).hexdigest(), expected, name)

    def test_static_completion_does_not_enable_learning_or_sentence_suggestions(self):
        schema = (CUSTOM / 'niuma_glass.schema.yaml').read_text(encoding='utf-8')
        translator = schema.split('\ntranslator:\n', 1)[1].split('\nmenu:\n', 1)[0]
        for line in (
            '  dictionary: niumawb', '  enable_completion: true', "    - 'xform/^~([a-z]+)$/$1/'",
            '  enable_user_dict: false', '  enable_sentence: false', '  enable_encoder: false',
            '  encode_commit_history: false',
        ):
            self.assertIn(line, translator.splitlines())

    def test_period_still_not_bound_to_page_down(self):
        schema = (CUSTOM / 'niuma_glass.schema.yaml').read_text(encoding='utf-8')
        active = '\n'.join(line for line in schema.splitlines() if not line.lstrip().startswith('#'))
        self.assertNotRegex(active, r'accept:\s*period\s*,\s*send:\s*Page_Down')
        self.assertIn('{ when: paging, accept: comma, send: Page_Up }', active)


if __name__ == '__main__':
    unittest.main()

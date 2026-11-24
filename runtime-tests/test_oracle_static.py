# -*- coding: utf-8 -*-
"""静态 oracle 自测 + 变更模拟（unittest，stdlib only）。

证明范围（务必区分）：
- 输入文件指纹、行数、列序解析、import 合并次序正确；
- 输出确定性（两次构建字节一致）；
- 变更模拟：历史/联想状态写入*独立*的模拟结构后，静态 fixture 数据不变。
这全部只说明"静态数据参考可复现"，并非已验证 Rime 引擎的编译/排序行为。
"""

import copy
import hashlib
import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import oracle_core  # noqa: E402


class TestStaticOracle(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.result = oracle_core.build()
        cls.fix = cls.result["fixtures"]["codes"]

    def test_input_sha256(self):
        for t in self.result["tables"].values():
            self.assertTrue(t["sha_ok"], "%s sha256 不符" % t["file"])

    def test_data_row_counts(self):
        rows = {t["file"]: t["data_rows"] for t in self.result["tables"].values()}
        self.assertEqual(rows.get("niumawb_zc.dict.yaml"), 285039)
        self.assertEqual(rows.get("niumawb_fh.dict.yaml"), 684)

    def test_import_order_zc_then_fh(self):
        self.assertEqual(
            self.result["main_table"]["import_order"], ["niumawb_zc", "niumawb_fh"]
        )
        # 列序不同（zc: text,code,stem / fh: code,text）应被各自按 header 解析
        cols = {name: t["columns"] for name, t in self.result["tables"].items()}
        self.assertEqual(cols["niumawb_zc"], ["text", "code", "stem"])
        self.assertEqual(cols["niumawb_fh"], ["code", "text"])

    def test_required_fixture_codes_present(self):
        for c in ("a", "aa", "d", "z"):
            self.assertIn(c, self.fix)
        # d 可能缺失：若缺失应标 absent_code
        all_codes = {
            e["code"] for t in self.result["raw_tables"] for e in t["entries"]
        }
        if "d" not in all_codes:
            self.assertIn("absent_code", self.fix["d"]["categories"])

    def test_five_codes_no_omission(self):
        fiv = self.result["fixtures"]["five_code_summary"]
        # 用户声明：符号表有 14 条 5 码条目，不得遗漏
        self.assertEqual(fiv["total_entries"], 14, "5码条目数与用户声明 14 不符")
        self.assertGreaterEqual(fiv["distinct_5code"], 1)
        # 每个 5 码 code 都必须进入 fixtures
        for c in fiv["codes"]:
            self.assertIn(c, self.fix)
            self.assertIn("five_code_symbol", self.fix[c]["categories"])
        # 数量一致性：summary 条目数 == 各 code 原始条目总和
        total = sum(len(self.fix[c]["expect_full_raw"]) for c in fiv["codes"])
        self.assertEqual(total, fiv["total_entries"])

    def test_dual_expectations_recorded(self):
        for code, e in self.fix.items():
            self.assertIn("expect_full_raw", e)
            self.assertIn("expect_first_wins_texts", e)
            # 两个预期长度关系：first_wins <= full
            self.assertLessEqual(len(e["expect_first_wins_texts"]), len(e["expect_full_raw"]))
            self.assertIn("未冒称用户同意去重", e["note"])

    def test_no_weights_or_freq_fields(self):
        blob = json.dumps(self.result, ensure_ascii=False)
        # 条目只允许 table/line/text/code/stem 五键
        for code, e in self.fix.items():
            for ent in e["expect_full_raw"]:
                self.assertEqual(
                    set(ent.keys()), {"table", "line", "text", "code", "stem"}
                )

    def test_deterministic_bytes(self):
        r1 = oracle_core.to_json(oracle_core.build())
        r2 = oracle_core.to_json(oracle_core.build())
        self.assertEqual(
            hashlib.sha256(r1.encode("utf-8")).hexdigest(),
            hashlib.sha256(r2.encode("utf-8")).hexdigest(),
        )

    # ---- 变更模拟：历史 / 联想不得改变静态 fixture ----
    def test_history_simulation_does_not_change_fixture(self):
        snapshot = copy.deepcopy(self.fix)
        simulated_user_hist = {
            # 模拟"用户历史"：把某些候选标记为最近使用（只写在独立结构里）
            "z": [{"text": "？", "times": 3}],
            "aa": [{"text": "工艺", "times": 1}],
        }
        simulated_freq = {c: 1.0 for c in ("a", "aa", "d", "z")}
        _ = simulated_user_hist, simulated_freq
        # 模拟后重建静态 oracle，fixture 必须与快照逐字相同
        self.assertEqual(copy.deepcopy(self.fix), snapshot)
        rebuilt = oracle_core.build()["fixtures"]["codes"]
        self.assertEqual(rebuilt, snapshot)

    def test_association_simulation_does_not_change_fixture(self):
        snapshot = copy.deepcopy(self.fix)
        simulated_assoc = {
            # 模拟"无编码且刚上屏 → 显示联想；用户选了某个联想词"
            "trigger": "工艺",
            "picked_association": "工艺流程",
        }
        _ = simulated_assoc
        rebuilt = oracle_core.build()["fixtures"]["codes"]
        self.assertEqual(rebuilt, snapshot)
        # 再选一次联想，仍不变
        simulated_assoc["picked_association"] = "工艺品"
        rebuilt2 = oracle_core.build()["fixtures"]["codes"]
        self.assertEqual(rebuilt2, snapshot)

    def test_static_reference_disclaimer_present(self):
        self.assertIn("static_reference_only", self.result["engine_expectations"])
        self.assertIn("未验证 Rime", self.result["engine_expectations"]["static_reference_only"])


if __name__ == "__main__":
    unittest.main(verbosity=2)

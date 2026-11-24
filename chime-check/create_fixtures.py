# Copyright 2026 Luke Steuber
# SPDX-License-Identifier: Apache-2.0
"""Generate checked-in numeric fixtures from the upstream NumPy reference."""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
UPSTREAM = ROOT / "upstream"
sys.path.insert(0, str(UPSTREAM))
import inference

MODEL = inference.LocalTypeModel(UPSTREAM / "cifg_int8.bin")
CASES = [
    "",
    "I",
    "I am",
    "I am ",
    "how are you",
    "how are you ",
    "I don't",
    "I don’t ",
    "hello\nI am",
    "meet me at 12:30 ",
    "thanks!!! ",
    "qzxvplm ",
    "this is a long context " + "very " * 70 + "good",
]
fixtures = []
for text in CASES:
    logits, prefix = MODEL.logits(text)
    suggestions = MODEL.predict(text, 6)
    ids = [MODEL.tokenizer.id_of(word) for word in suggestions]
    fixtures.append({
        "text": text,
        "prefix": prefix,
        "top6": suggestions,
        "logitIds": ids,
        "logits": [float(logits[i]) for i in ids],
    })
(ROOT / "reference-fixtures.json").write_text(
    json.dumps({"license": "Apache-2.0 upstream model/reference; fixture generation only", "cases": fixtures}, indent=2, ensure_ascii=False) + "\n",
    encoding="utf-8",
)
print(f"wrote {len(fixtures)} cases to {ROOT / 'reference-fixtures.json'}")

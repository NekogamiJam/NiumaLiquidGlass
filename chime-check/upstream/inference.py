# Copyright 2026 Luke Steuber
# SPDX-License-Identifier: Apache-2.0
"""Chime-SMS-2M NumPy reference inference. Author: Luke Steuber.

The int8 file is dequantized to float64.
This is an offline reference implementation; LocalType runs the same model in Kotlin.
License: Apache-2.0; see LICENSE.md and Apache-2.0.txt.
"""
from __future__ import annotations
import argparse
import json
import struct
import sys
import unicodedata
import zlib
from pathlib import Path
import numpy as np
# Python resolves script symlinks for sys.path[0]. HF cache snapshots link each
# file into a blob store, so import siblings from the requested snapshot path.
sys.path.insert(0, str(Path(__file__).absolute().parent))
import localtype_tokenizer


def tokenize_module():
    return localtype_tokenizer

MATRICES = ("embedding", "wx", "wr", "proj")

BIN_MAGIC = b"LTCF"

BIN_VERSION = 1

HEADER = struct.Struct("<4s5I")

def _pad4(n: int) -> int:
    return (-n) % 4

def read_bin(data: bytes) -> tuple[dict[str, np.ndarray], list[str]]:
    """The inverse of bin_bytes, checking the magic, version, length and CRC."""
    magic, version, V, E, H, n_vocab = HEADER.unpack_from(data, 0)
    if magic != BIN_MAGIC or version != BIN_VERSION:
        raise ValueError(f"not a version {BIN_VERSION} {BIN_MAGIC!r} file")
    (crc,) = struct.unpack_from("<I", data, len(data) - 4)
    if crc != zlib.crc32(data[:-4]):
        raise ValueError("CRC mismatch")
    q: dict[str, np.ndarray] = {}
    pos = HEADER.size
    for name, (rows, cols) in (("embedding", (V, E)), ("wx", (E, 3 * H)), ("wr", (E, 3 * H)), ("proj", (H, E))):
        q[f"{name}_scale"] = np.frombuffer(data, dtype="<f4", count=rows, offset=pos).astype(np.float32)
        pos += 4 * rows
        q[f"{name}_q"] = np.frombuffer(data, dtype=np.int8, count=rows * cols, offset=pos).reshape(rows, cols).copy()
        pos += rows * cols + _pad4(rows * cols)
    q["b"] = np.frombuffer(data, dtype="<f4", count=3 * H, offset=pos).astype(np.float32)
    pos += 12 * H
    tokens = data[pos : pos + n_vocab].decode("utf-8").split("\n")
    if tokens[-1] != "" or len(tokens) != V + 1 or pos + n_vocab + 4 != len(data):
        raise ValueError("vocabulary section does not match the header")
    return q, tokens[:-1]

CONTEXT_TOKENS = 64

CONTEXT_HOP = 32

SENTENCE_END_TOKENS = (".", "!", "?", "…")

def window_start(length: int, limit: int = CONTEXT_TOKENS - 1, hop: int = CONTEXT_HOP) -> int:
    """Where the context starts in a sentence of ``length`` tokens (CifgTokenizer.windowStart).

    0 while the sentence fits in ``limit`` tokens, then the smallest multiple of ``hop`` that
    leaves at most ``limit``: a 63-token sentence is read whole, a 64-token one from token 32,
    a 96-token one from token 64. ``hop=1`` is a window that slides one token at a time.
    """
    if not 1 <= hop <= limit:
        raise ValueError(f"hop {hop} must be in 1..{limit}")
    return 0 if length <= limit else -(-(length - limit) // hop) * hop

def context_tokens(text: str, limit: int = CONTEXT_TOKENS - 1, hop: int = CONTEXT_HOP) -> tuple[list[str], str]:
    """The tokens a model sees for the text before the cursor, and the normalized typed prefix.

    The word being typed is left out and returned as the prefix. The context is the current
    sentence: what follows the last newline, then the tokens after the last . ! ? or … token.
    A sentence longer than ``limit`` tokens is read from ``window_start``.
    """
    tk = tokenize_module()
    prefix = tk.active_prefix(text)
    before = text[: len(text) - len(prefix)]
    toks = tk.tokenize(before.rsplit("\n", 1)[-1])
    for i in range(len(toks) - 1, -1, -1):
        if toks[i] in SENTENCE_END_TOKENS:
            toks = toks[i + 1 :]
            break
    return toks[window_start(len(toks), limit, hop) :], tk.normalize(prefix)

def context_ids(tokenizer, text: str) -> tuple[list[int], str]:
    toks, prefix = context_tokens(text)
    return [tokenizer.bos_id] + [tokenizer.id_of(t) for t in toks], prefix

def dequantize64(q: dict[str, np.ndarray]) -> dict[str, np.ndarray]:
    out = {name: q[f"{name}_q"].astype(np.float64) * q[f"{name}_scale"].astype(np.float64).reshape(-1, 1) for name in MATRICES}
    out["b"] = q["b"].astype(np.float64)
    return out

def reference_logits(w: dict[str, np.ndarray], ids: list[int]) -> np.ndarray:
    """CIFGLM.__call__ for one sequence from the zero state, in float64; logits after the last id."""
    H = w["proj"].shape[0]
    c = np.zeros(H)
    r = np.zeros(w["embedding"].shape[1])
    for t in ids:
        g = w["embedding"][t] @ w["wx"] + r @ w["wr"] + w["b"]
        f = 1.0 / (1.0 + np.exp(-g[:H]))
        o = 1.0 / (1.0 + np.exp(-g[H : 2 * H]))
        u = np.tanh(g[2 * H :])
        c = f * c + (1.0 - f) * u
        r = (o * np.tanh(c)) @ w["proj"]
    return w["embedding"] @ r

def offerable(token: str) -> bool:
    """A word the keyboard could offer: the whole token matches
    [\\p{L}][\\p{L}'’-]* and its UTF-16 length is 2 or more, or it is a or i.
    Specials, punctuation and emoji are not words."""
    if not token or not unicodedata.category(token[0]).startswith("L"):
        return False
    if not all(unicodedata.category(ch).startswith("L") or ch in "'’-" for ch in token):
        return False
    return len(token.encode("utf-16-le")) // 2 >= 2 or token in ("a", "i")

def top_words(logits: np.ndarray, tokens: list[str], offer_ids: list[int], prefix: str, k: int) -> list[str]:
    """The k best offerable words that start with prefix, by logit, ties to the lower id."""
    cand = [i for i in offer_ids if tokens[i].startswith(prefix)]
    cand.sort(key=lambda i: (-logits[i], i))
    return [tokens[i] for i in cand[:k]]

class LocalTypeModel:
    """Stateless next-word/completion ranking from text before the cursor."""

    def __init__(self, path=None):
        path = Path(path) if path is not None else Path(__file__).with_name("cifg_int8.bin")
        self.quantized, tokens = read_bin(path.read_bytes())
        self.tokenizer = localtype_tokenizer.Tokenizer(tokens)
        self.weights = dequantize64(self.quantized)
        self.offer_ids = [i for i, token in enumerate(tokens) if offerable(token)]

    def logits(self, text):
        ids, prefix = context_ids(self.tokenizer, text)
        return reference_logits(self.weights, ids), prefix

    def predict(self, text, k=6):
        if not 1 <= k <= 64:
            raise ValueError("k must be in 1..64")
        logits, prefix = self.logits(text)
        return top_words(logits, self.tokenizer.tokens, self.offer_ids, prefix, k)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("text", help="Text before the cursor; a trailing space completes a word")
    parser.add_argument("--model", type=Path, default=Path(__file__).with_name("cifg_int8.bin"))
    parser.add_argument("-k", type=int, default=6)
    args = parser.parse_args()
    model = LocalTypeModel(args.model)
    print(json.dumps({"text": args.text, "suggestions": model.predict(args.text, args.k)}, ensure_ascii=False))


if __name__ == "__main__":
    main()

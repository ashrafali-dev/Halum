#!/usr/bin/env python3
"""Generate/verify fixtures/lens_*.bin without any external dependency.

The committed fixtures are genuine protobuf `LensOverlayServerResponse` bytes
produced by the official jspb bindings of rxliuli/chrome-lens-ocr (MIT); see
fixtures/lens/README.md for the Node procedure used.

This script re-encodes the same messages with the same field numbers, so CI
can prove `app.halum.core.LensProtocol` reads the agreed wire format even if
the binary fixtures are ever lost.

  python3 tools/lens_fixture.py --out fixtures            # write .bin + .json
  python3 tools/lens_fixture.py --out fixtures --check    # compare only
"""
from __future__ import annotations

import argparse
import json
import os
import struct
import sys

WIDTH = 800
HEIGHT = 1200

# name -> (language, [ ([ (word, separator)... ], [cx, cy, w, h]) ... ])
PAGES = {
    "lens_ja": (
        "ja",
        [
            ([("勇者", ""), ("は", ""), ("魔王", ""), ("を", ""), ("倒した", "。")], (0.25, 0.12, 0.30, 0.06)),
            ([("だが", ""), ("、", ""), ("その", ""), ("代償", ""), ("は", ""), ("大きかった", "。")], (0.72, 0.30, 0.34, 0.05)),
            ([("これ", ""), ("で", ""), ("終わり", ""), ("だ", "。")], (0.30, 0.55, 0.22, 0.04)),
            ([("そう", ""), ("思って", ""), ("いた", "…")], (0.30, 0.61, 0.22, 0.04)),
            ([("まだ", ""), ("何か", ""), ("が", ""), ("残っている", "…")], (0.70, 0.80, 0.30, 0.05)),
        ],
    ),
    "lens_ko": (
        "ko",
        [
            ([("네", ""), ("가", ""), ("그걸", ""), ("믿어", "?")], (0.22, 0.15, 0.28, 0.06)),
            ([("그래", ""), ("서", ""), ("어쩌라고", "?")], (0.75, 0.42, 0.26, 0.05)),
            ([("난", ""), ("다시", ""), ("돌아갈", ""), ("거야", "!")], (0.40, 0.75, 0.30, 0.05)),
        ],
    ),
}


def varint(n: int) -> bytes:
    out = bytearray()
    while True:
        low = n & 0x7F
        n >>= 7
        if n:
            out.append(low | 0x80)
        else:
            out.append(low)
            return bytes(out)


def len_field(field: int, payload: bytes) -> bytes:
    return varint((field << 3) | 2) + varint(len(payload)) + payload


def f32_field(field: int, value: float) -> bytes:
    return varint((field << 3) | 5) + struct.pack("<f", value)


def varint_field(field: int, value: int) -> bytes:
    return varint(field << 3) + varint(value)


def str_field(field: int, value: str) -> bytes:
    return len_field(field, value.encode("utf-8"))


def word(text: str, sep: str) -> bytes:
    # TextLayout.Word: 2 = plain_text, 3 = text_separator
    payload = str_field(2, text)
    if sep:
        payload += str_field(3, sep)
    return payload


def line(words, box) -> bytes:
    cx, cy, w, h = box
    # CenterRotatedBox: 1 center_x, 2 center_y, 3 width, 4 height, 5 rotation_z,
    # 6 coordinate_type (1 = NORMALIZED)
    bbox = (
        f32_field(1, cx)
        + f32_field(2, cy)
        + f32_field(3, w)
        + f32_field(4, h)
        + f32_field(5, 0.0)
        + varint_field(6, 1)
    )
    geometry = len_field(1, bbox)  # Geometry.bounding_box = 1
    body = b"".join(len_field(1, word(t, s)) for t, s in words)  # Line.words = 1
    return body + len_field(2, geometry)  # Line.geometry = 2


def page(lang: str, lines) -> bytes:
    # One Paragraph per Line here: Lens returns a paragraph per text line.
    paras = b"".join(
        len_field(2, line(words, box)) + str_field(5, lang)  # Paragraph.lines = 2, content_language = 5
        for words, box in lines
    )
    layout = paras  # TextLayout.paragraphs = 1 (repeated), encoded inline above
    text = len_field(1, layout) + str_field(2, lang)  # Text.text_layout = 1, content_language = 2
    objects = len_field(3, text)  # ObjectsResponse.text = 3
    return len_field(2, objects)  # ServerResponse.objects_response = 2


def join_words(words) -> str:
    out = ""
    for i, (t, sep) in enumerate(words):
        out += t
        if sep:
            out += sep
        elif i < len(words) - 1:
            out += " "
    return " ".join(out.split())


def expected_blocks(lang: str, lines) -> list[dict]:
    blocks = []
    for words, (cx, cy, bw, bh) in lines:
        x1 = cx * WIDTH - bw * WIDTH / 2
        y1 = cy * HEIGHT - bh * HEIGHT / 2
        x2 = cx * WIDTH + bw * WIDTH / 2
        y2 = cy * HEIGHT + bh * HEIGHT / 2
        blocks.append(
            {"text": join_words(words), "bbox": [round(v, 3) for v in (x1, y1, x2, y2)], "script": lang}
        )
    return blocks


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    failed = False
    for name, (lang, lines) in PAGES.items():
        blob = page(lang, lines)
        expected = {"width": WIDTH, "height": HEIGHT, "blocks": expected_blocks(lang, lines)}
        bin_path = os.path.join(args.out, f"{name}.bin")
        json_path = os.path.join(args.out, f"{name}.expected.json")
        if args.check:
            with open(bin_path, "rb") as f:
                if f.read() != blob:
                    print(f"[lens_fixture] MISMATCH {bin_path}", file=sys.stderr)
                    failed = True
            continue
        with open(bin_path, "wb") as f:
            f.write(blob)
        with open(json_path, "w") as f:
            json.dump(expected, f, indent=2, ensure_ascii=False)
            f.write("\n")
        print(f"[lens_fixture] {bin_path} {len(blob)} bytes")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Decode and re-encode fixtures/lens_*.bin with no external dependency.

The committed fixtures are genuine protobuf `LensOverlayServerResponse` bytes
produced by the official jspb bindings of rxliuli/chrome-lens-ocr (MIT); see
fixtures/lens/README.md. This script:

  --check: decode .bin -> blocks, compare to .expected.json, then assert
           decode(encode(decode(x))) == decode(x).
  --write: regenerate the .bin from .expected.json (only for a deliberate
           fixture change).

It exists so CI can prove the repo's understanding of the wire layout without
Node, jspb or network. If it and `app.halum.core.LensProtocol` disagree, one of
them is wrong and the test should fail.
"""
from __future__ import annotations

import argparse
import json
import os
import struct
import sys

# Message types produced by jspb that are not repeated in the wire format.
WIRE_VARINT = 0
WIRE_FIXED64 = 1
WIRE_LEN = 2
WIRE_FIXED32 = 5


class Proto:
    """Bounded reader: pos/end, never past the parent message (same as the
    Kotlin Proto.Reader; an unbounded one silently desyncs on nested fields)."""

    def __init__(self, buf: bytes, pos: int = 0, end: int | None = None):
        self.buf = buf
        self.pos = pos
        self.end = len(buf) if end is None else end

    def more(self) -> bool:
        return self.pos < self.end

    def varint(self) -> int:
        out = 0
        shift = 0
        while True:
            if self.pos >= self.end:
                raise ValueError("truncated varint")
            b = self.buf[self.pos]
            self.pos += 1
            out |= (b & 0x7F) << shift
            if not b & 0x80:
                return out
            shift += 7

    def tag(self) -> tuple[int, int]:
        raw = self.varint()
        return raw >> 3, raw & 7

    def fixed32(self) -> float:
        if self.pos + 4 > self.end:
            raise ValueError("truncated fixed32")
        (v,) = struct.unpack("<f", self.buf[self.pos:self.pos + 4])
        self.pos += 4
        return v

    def length(self) -> "Proto":
        n = self.varint()
        if self.pos + n > self.end:
            raise ValueError("length exceeds parent message")
        sub = Proto(self.buf, self.pos, self.pos + n)
        self.pos += n
        return sub

    def string(self) -> str:
        sub = self.length()
        return sub.buf[sub.pos:sub.end].decode("utf-8")

    def skip(self, wire: int) -> None:
        if wire == WIRE_VARINT:
            self.varint()
        elif wire == WIRE_LEN:
            self.pos += self.varint()
        elif wire == WIRE_FIXED32:
            self.pos += 4
        elif wire == WIRE_FIXED64:
            self.pos += 8
        else:
            raise ValueError(f"unsupported wire type {wire}")


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
    return varint((field << 3) | WIRE_FIXED32) + struct.pack("<f", value)


def varint_field(field: int, value: int) -> bytes:
    return varint(field << 3) + varint(value)


def str_field(field: int, value: str) -> bytes:
    return len_field(field, value.encode("utf-8"))


def join_words(words: list[tuple[str, str]]) -> str:
    out = ""
    for i, (text, sep) in enumerate(words):
        out += text
        if sep:
            out += sep
        elif i < len(words) - 1:
            out += " "
    return " ".join(out.split())


def decode_block(paragraph: Proto, lang: str, width: int, height: int) -> list[dict]:
    lines = []
    box = None
    para_lang = ""
    while paragraph.more():
        field, wire = paragraph.tag()
        if field == 2 and wire == WIRE_LEN:
            lines.append(paragraph.length())
        elif field == 3 and wire == WIRE_LEN:
            box = paragraph.length()
        elif field == 5 and wire == WIRE_LEN:
            para_lang = paragraph.string()
        else:
            paragraph.skip(wire)
    para_box = decode_box(box, width, height) if box else None
    lang = para_lang or lang

    blocks = []
    for line in lines:
        words: list[tuple[str, str]] = []
        line_box = para_box
        while line.more():
            field, wire = line.tag()
            if field == 1 and wire == WIRE_LEN:
                w = line.length()
                text, sep = "", ""
                while w.more():
                    wf, ww = w.tag()
                    if wf == 2 and ww == WIRE_LEN:
                        text = w.string()
                    elif wf == 3 and ww == WIRE_LEN:
                        sep = w.string()
                    else:
                        w.skip(ww)
                words.append((text, sep))
            elif field == 2 and wire == WIRE_LEN:
                line_box = decode_box(line.length(), width, height)
            else:
                line.skip(wire)
        text = join_words(words)
        if not text or line_box is None:
            continue
        blocks.append({"text": text, "bbox": line_box, "script": lang})
    return blocks


def decode_box(geometry: Proto, width: int, height: int):
    bbox = None
    while geometry.more():
        field, wire = geometry.tag()
        if field == 1 and wire == WIRE_LEN:
            b = geometry.length()
            vals = [0.0] * 6
            while b.more():
                f, w = b.tag()
                if f in (1, 2, 3, 4, 5) and w == WIRE_FIXED32:
                    vals[f - 1] = b.fixed32()
                elif f == 6 and w == WIRE_VARINT:
                    vals[5] = float(b.varint())
                else:
                    b.skip(w)
            bbox = vals
        else:
            geometry.skip(wire)
    if bbox is None:
        return None
    cx, cy, bw, bh = bbox[:4]
    x1 = cx * width - bw * width / 2
    y1 = cy * height - bh * height / 2
    x2 = cx * width + bw * width / 2
    y2 = cy * height + bh * height / 2
    return [round(v, 3) for v in (x1, y1, x2, y2)]


def decode_response(data: bytes, width: int, height: int) -> list[dict]:
    root = Proto(data)
    blocks: list[dict] = []
    while root.more():
        field, wire = root.tag()
        if field == 1:  # ServerError
            raise SystemExit("fixture contains a ServerError")
        if field == 2 and wire == WIRE_LEN:
            objects = root.length()
            while objects.more():
                of, ow = objects.tag()
                if of == 3 and ow == WIRE_LEN:
                    text = objects.length()
                    lang = ""
                    layout = None
                    while text.more():
                        tf, tw = text.tag()
                        if tf == 1 and tw == WIRE_LEN:
                            layout = text.length()
                        elif tf == 2 and tw == WIRE_LEN:
                            lang = text.string()
                        else:
                            text.skip(tw)
                    while layout and layout.more():
                        pf, pw = layout.tag()
                        if pf == 1 and pw == WIRE_LEN:
                            blocks += decode_block(layout.length(), lang, width, height)
                        else:
                            layout.skip(pw)
                else:
                    objects.skip(ow)
        else:
            root.skip(wire)
    return blocks


def encode_box(bbox: list[float], width: int, height: int) -> bytes:
    """Normalised centre/size box back to the wire form."""
    x1, y1, x2, y2 = bbox
    cx = (x1 + x2) / 2 / width
    cy = (y1 + y2) / 2 / height
    bw = (x2 - x1) / width
    bh = (y2 - y1) / height
    b = (
        f32_field(1, cx)
        + f32_field(2, cy)
        + f32_field(3, bw)
        + f32_field(4, bh)
        + f32_field(5, 0.0)
        + varint_field(6, 1)  # NORMALIZED
    )
    return len_field(1, b)


def encode_line(block: dict, width: int, height: int) -> bytes:
    """TextLine: 1 = Word (repeated), 2 = line geometry.

    Word carries its text at its own field 2 (Word.plain_text), so the line
    holds one nested word message per logical text run.
    """
    word = str_field(2, block["text"])
    return len_field(1, word) + len_field(2, encode_box(block["bbox"], width, height))


def encode_page(blocks: list[dict], lang: str, width: int, height: int) -> bytes:
    """Rebuild a ServerResponse from decoded blocks.

    Layout mirrors what `decode_block` reads: TextLayout.paragraphs = 1
    (repeated) holds one field-1 entry per block; each entry carries
    lines = 2 (repeated), geometry = 3, language = 5. Text.text_layout = 1,
    Text.content_language = 2, ObjectsResponse.text = 3,
    ServerResponse.objects_response = 2.
    """
    paras = b"".join(
        len_field(
            1,
            len_field(2, encode_line(b, width, height))
            + len_field(3, encode_box(b["bbox"], width, height))
            + str_field(5, b.get("script") or lang),
        )
        for b in blocks
    )
    text = len_field(1, paras) + str_field(2, lang)
    return len_field(2, len_field(3, text))


def write(json_path: str, bin_path: str) -> bool:
    """Regenerate the .bin from .expected.json with our own encoder."""
    with open(json_path) as f:
        exp = json.load(f)
    if not exp["blocks"]:
        print(f"[lens_fixture] {json_path}: no blocks", file=sys.stderr)
        return False
    data = encode_page(
        exp["blocks"],
        exp["blocks"][0].get("script", ""),
        exp["width"],
        exp["height"],
    )
    with open(bin_path, "wb") as f:
        f.write(data)
    print(f"[lens_fixture] wrote {bin_path} ({len(data)} bytes)")
    return True


def check(bin_path: str, json_path: str) -> bool:
    with open(bin_path, "rb") as f:
        data = f.read()
    with open(json_path) as f:
        exp = json.load(f)
    blocks = decode_response(data, exp["width"], exp["height"])
    if blocks != exp["blocks"]:
        print(f"[lens_fixture] {bin_path}: decoded blocks differ from expected", file=sys.stderr)
        for got, want in zip(blocks, exp["blocks"]):
            if got != want:
                print(f"   got {got}\n  want {want}", file=sys.stderr)
                break
        return False
    rebuilt = encode_page(blocks, blocks[0]["script"] if blocks else "", exp["width"], exp["height"])
    # Word-level fields (TextEntityIdentifier, separators per word) are not
    # reconstructed, so byte identity with a live jspb response is not
    # expected. What must hold is decode(encode(decode(x))) == decode(x).
    if decode_response(rebuilt, exp["width"], exp["height"]) != blocks:
        print(f"[lens_fixture] {bin_path}: our encoder does not round-trip", file=sys.stderr)
        print(f"   got  {decode_response(rebuilt, exp['width'], exp['height'])}", file=sys.stderr)
        return False
    print(f"[lens_fixture] {bin_path}: {len(blocks)} blocks OK")
    return True


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="decode and verify (default)")
    mode.add_argument("--write", action="store_true", help="regenerate .bin from .expected.json")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    ok = True
    for name in ("lens_ja", "lens_ko"):
        bin_path = os.path.join(args.out, f"{name}.bin")
        json_path = os.path.join(args.out, f"{name}.expected.json")
        if not os.path.exists(json_path):
            print(f"[lens_fixture] missing {json_path}", file=sys.stderr)
            ok = False
            continue
        try:
            if args.write:
                ok = write(json_path, bin_path) and ok
            else:
                if not os.path.exists(bin_path):
                    print(f"[lens_fixture] missing {bin_path}", file=sys.stderr)
                    ok = False
                    continue
                ok = check(bin_path, json_path) and ok
        except (ValueError, IndexError, KeyError) as exc:
            print(f"[lens_fixture] {bin_path}: failed: {exc}", file=sys.stderr)
            ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

# NOTICE

Halum is licensed under the **GNU General Public License v3.0** (see `LICENSE`).

This file lists third-party code and assets that are ported, vendored, or
downloaded by the build. **Add an entry in the same commit that adds a
ported file.** GPL-3.0 §5/§6 require attribution and licence propagation.

## Ported code

| File in this repo | Upstream | Licence | Changes |
|---|---|---|---|
| `app/src/main/java/app/halum/core/Proto.kt` | written for Halum (wire format per protobuf spec) | GPL-3.0 | n/a — original |
| `app/src/main/java/app/halum/core/LensProtocol.kt` | derived from `rxliuli/chrome-lens-ocr` (`src/core.js`, `src/consts.js`, `src/utils/proto_generated/*`) | MIT | Rewritten in Kotlin; protobuf field numbers, enum values, endpoint and the paragraph→line→word decode path preserved. No HTTP/cookie/image code ported. |
| `tools/lens_fixture.py` | same project (field numbers) | MIT | Independent Python encoder mirroring the wire layout to verify the committed fixtures. |

MIT licence notice for `chrome-lens-ocr`:

```
MIT License

Copyright (c) rxliuli

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Bundled assets

| Asset | Source | Licence |
|---|---|---|
| `res/font/NotoSansBengali-*.ttf` | Google Noto | SIL Open Font License 1.1 |
| `assets/js/chat_bridge.js` | written for Halum | GPL-3.0 |

## Downloaded at build time (not redistributed in source)

| Artifact | Source | Licence |
|---|---|---|
| `best.pt` (bubble segmentation) | `juithealien/manga109-segmentation-bubble` | Apache-2.0 |
| `migan.onnx` (inpainting) | MI-GAN (Picsart AI Research) | check upstream; recorded in `.halum/MODELS.md` |

Exported `.tflite` files derived from these are distributed only inside the
GPL-3.0 app binary and follow the more restrictive upstream licence.

## Test fixtures

| Fixture | Source | Licence |
|---|---|---|
| `fixtures/bubbles/images/*.jpg` | `zbu1/manga-translator-cli/docs` | see upstream repo (MIT) |
| `fixtures/lens_ja.bin`, `fixtures/lens_ko.bin` | generated with `rxliuli/chrome-lens-ocr` jspb bindings | MIT |

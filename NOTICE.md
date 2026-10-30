# NOTICE

Halum is licensed under the **GNU General Public License v3.0** (see `LICENSE`).

This file lists third-party code and assets that are ported, vendored, or
downloaded by the build. **Add an entry in the same commit that adds a
ported file.** GPL-3.0 §5/§6 require attribution and licence propagation.

## Ported code

| File in this repo | Upstream | Licence | Changes |
|---|---|---|---|
| `app/src/main/java/app/halum/core/Proto.kt` | written for Halum (wire format per protobuf spec) | GPL-3.0 | n/a — original |
| `app/src/main/java/app/halum/ocr/LensProtocol.kt` | derived from `rxliuli/chrome-lens-ocr` (`src/`, TypeScript) | MIT | Rewritten in Kotlin; field numbers and request/response shape preserved; transport extracted behind `LensTransport` |

MIT licence text for `chrome-lens-ocr` is reproduced verbatim below the table
when the port lands (Phase 1).

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

#!/usr/bin/env python3
"""Size gates for AC10.

Usage: report_sizes.py <apk> <models_dir>

Enforces:
  APK <= 60 MB
  sum(model assets) <= 35 MB
  each model <= 25 MB
  APK contains lib/armeabi-v7a/*.so  (32-bit support is a hard requirement)

Exit code 1 on any violation. Prints a table either way.
"""
import os
import sys
import zipfile

MB = 1_000_000
APK_LIMIT = 60 * MB
MODELS_LIMIT = 35 * MB
MODEL_LIMIT = 25 * MB


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    apk_path, models_dir = sys.argv[1], sys.argv[2]
    errors = []

    apk_size = os.path.getsize(apk_path)
    print(f"APK            {apk_size / MB:8.2f} MB  (limit {APK_LIMIT // MB} MB)  {apk_path}")
    if apk_size > APK_LIMIT:
        errors.append(f"APK exceeds {APK_LIMIT // MB} MB")

    total = 0
    model_files = []
    if os.path.isdir(models_dir):
        for name in sorted(os.listdir(models_dir)):
            p = os.path.join(models_dir, name)
            if not os.path.isfile(p) or name.startswith("."):
                continue
            size = os.path.getsize(p)
            total += size
            model_files.append((name, size))
            print(f"model {name:28s} {size / MB:8.2f} MB  (limit {MODEL_LIMIT // MB} MB)")
            if size > MODEL_LIMIT:
                errors.append(f"{name} exceeds {MODEL_LIMIT // MB} MB")
    else:
        print(f"models dir missing: {models_dir}")
        if os.environ.get("HALUM_REQUIRE_MODELS", "1") == "1":
            errors.append("no model assets were exported (HALUM_REQUIRE_MODELS=1)")

    print(f"models total   {total / MB:8.2f} MB  (limit {MODELS_LIMIT // MB} MB)")
    if total > MODELS_LIMIT:
        errors.append(f"model assets exceed {MODELS_LIMIT // MB} MB")

    with zipfile.ZipFile(apk_path) as z:
        libs = [n for n in z.namelist() if n.startswith("lib/")]
    abis = sorted({n.split("/")[1] for n in libs})
    print("APK ABIs       " + ", ".join(abis or ["<none>"]))
    if "armeabi-v7a" not in abis:
        errors.append("APK is missing armeabi-v7a native libraries")

    if errors:
        print("\nFAILED:", *errors, sep="\n  - ")
        return 1
    print("\nAll size gates passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

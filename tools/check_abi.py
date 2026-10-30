#!/usr/bin/env python3
"""D21: the default APK must be a universal fat APK.

Usage: check_abi.py <apk>

Asserts both lib/armeabi-v7a/ and lib/arm64-v8a/ are present and the APK is
<= 70 MB. Prints `APK_ABI_CHECK: v7a=… arm64=… sizeMB=… pass=…`, exits 1 on
failure.
"""
import os
import sys
import zipfile

MB = 1_000_000
APK_LIMIT = 70 * MB


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    apk = sys.argv[1]
    names = zipfile.ZipFile(apk).namelist()
    v7a = any(n.startswith("lib/armeabi-v7a/") for n in names)
    arm64 = any(n.startswith("lib/arm64-v8a/") for n in names)
    size = os.path.getsize(apk)
    ok = v7a and arm64 and size <= APK_LIMIT
    print(
        f"APK_ABI_CHECK: v7a={str(v7a).lower()} arm64={str(arm64).lower()} "
        f"sizeMB={size / MB:.1f} pass={str(ok).lower()}"
    )
    if not v7a:
        print("FAILED: missing lib/armeabi-v7a")
    if not arm64:
        print("FAILED: missing lib/arm64-v8a")
    if size > APK_LIMIT:
        print(f"FAILED: APK {size / MB:.1f} MB > {APK_LIMIT // MB} MB")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

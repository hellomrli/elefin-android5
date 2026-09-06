#!/usr/bin/env python3
"""Verify final artifacts, including the legacy signing identity required for API 21 updates."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def sdk_path():
    configured = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if configured:
        return Path(configured)
    local = ROOT / "local.properties"
    if local.is_file():
        for line in local.read_text().splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    raise SystemExit("Set ANDROID_HOME to the Android SDK directory.")


def run(*args):
    return subprocess.check_output([str(arg) for arg in args], text=True, stderr=subprocess.STDOUT)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--allow-unsigned", action="store_true", help="For pull-request verification only; never for published APKs")
    parser.add_argument("--apk-dir", type=Path, default=ROOT / "app/build/outputs/apk/release", help="Directory containing both APKs, including downloaded release assets")
    parser.add_argument("--release-tag", default="", help="Require a vMAJOR.MINOR.PATCH tag matching the APK version and in-app updater")
    args = parser.parse_args()
    build_tools = sdk_path() / "build-tools" / "36.0.0"
    expected_cert = (ROOT / "scripts/legacy_signing_sha256.txt").read_text().strip().lower()
    gradle = (ROOT / "app/build.gradle.kts").read_text()
    expected_id = re.search(r'^\s*applicationId\s*=\s*"([^"]+)"', gradle, re.M).group(1)
    expected_code = int(re.search(r'^\s*versionCode\s*=\s*(\d+)', gradle, re.M).group(1))
    expected_name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', gradle, re.M).group(1)
    if args.release_tag:
        version = re.fullmatch(r"v(\d+)\.(\d+)\.(\d+)", args.release_tag)
        assert version, "Release tag must be vMAJOR.MINOR.PATCH"
        major, minor, patch = map(int, version.groups())
        assert expected_code == major * 10000 + minor * 100 + patch, "Release tag does not match the updater version code"
        assert expected_name.split("-", 1)[0] == args.release_tag[1:], "Release tag does not match versionName"
    apks = args.apk_dir
    for abi in ("armeabi-v7a", "arm64-v8a"):
        apk = apks / f"elefin-release-{abi}.apk"
        assert apk.is_file(), f"Missing {apk.name}"
        badging = run(build_tools / "aapt", "dump", "badging", apk)
        identity = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
        assert identity, f"{apk.name}: missing package identity"
        assert identity.groups() == (expected_id, str(expected_code), expected_name), f"{apk.name}: package or version differs from the project"
        assert re.search(r"^sdkVersion:'21'$", badging, re.M), f"{apk.name}: minSdk must remain 21"
        assert re.search(r"^targetSdkVersion:'36'$", badging, re.M), f"{apk.name}: unexpected targetSdk"
        with zipfile.ZipFile(apk) as package:
            names = package.namelist()
            abis = {name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith(".so")}
            assert abis == {abi}, f"{apk.name}: ABI split mismatch: {abis}"
            assert "assets/fonts/DroidSansFallback.ttf" in names, "Missing first-install subtitle fallback"
            assert "assets/licenses/DroidSansFallback-NOTICE.txt" in names, "Missing font license"
            has_v1 = any(name.startswith("META-INF/") and name.endswith((".RSA", ".DSA", ".EC")) for name in names)
        if args.allow_unsigned and not has_v1:
            print(f"{apk.name}: {expected_name} ({expected_code}), API 21, {abi}, subtitle assets OK (unsigned verification build)")
            continue
        signature = run(build_tools / "apksigner", "verify", "--min-sdk-version", "21", "--verbose", "--print-certs", apk)
        assert "Verified using v1 scheme (JAR signing): true" in signature, f"{apk.name}: missing API 21 signature"
        certs = re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", signature)
        assert [cert.lower() for cert in certs] == [expected_cert], f"{apk.name}: signing identity changed; would break legacy updates"
        print(f"{apk.name}: {expected_name} ({expected_code}), API 21, {abi}, subtitle assets, v1 signature and legacy certificate OK")


if __name__ == "__main__":
    main()

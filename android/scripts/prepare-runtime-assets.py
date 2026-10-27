#!/usr/bin/env python3
"""Fetch the architecture-specific runtime that is embedded in the APK."""

from base64 import b64decode
from hashlib import sha256, sha512
from pathlib import Path
from urllib.request import urlopen
import os


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "runtime"

FILES = {
    "proot-arm64": (
        "https://skirsten.github.io/proot-portable-android-binaries/aarch64/proot",
        "sha256",
        "c902f35b3bce4013d2e78e3bf360b606523d55ab7b907578938577b243bfca38",
    ),
    "proot-x64": (
        "https://skirsten.github.io/proot-portable-android-binaries/x86_64/proot",
        "sha256",
        "8cda1248b1fd6cedd7c7f8ed5173db64ef335798b7726adc8d0684b2141faa7b",
    ),
    "ubuntu-rootfs-arm64.tar.xz": (
        "https://github.com/termux/proot-distro/releases/download/v4.18.0/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz",
        "sha256",
        "91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa",
    ),
    "ubuntu-rootfs-x64.tar.xz": (
        "https://github.com/termux/proot-distro/releases/download/v4.18.0/ubuntu-noble-x86_64-pd-v4.18.0.tar.xz",
        "sha256",
        "3a769bce23905effb504140d43b6ddd73dac9e261d1932894afa31de81e45414",
    ),
    "node-arm64.tar.xz": (
        "https://nodejs.org/dist/v22.19.0/node-v22.19.0-linux-arm64.tar.xz",
        "sha256",
        "0b2d9f564b6594222a62c82e1df2efe119dd4a4aff29644f4dd325bf360b6bcc",
    ),
    "node-x64.tar.xz": (
        "https://nodejs.org/dist/v22.19.0/node-v22.19.0-linux-x64.tar.xz",
        "sha256",
        "c0649af18e6a24f6fe5535a3e86b341dd49a8e71117c8b68bde973ef834f16f2",
    ),
    "omnirush.tgz": (
        "https://registry.npmjs.org/omnirush/-/omnirush-2.2.1.tgz",
        "sha512",
        "1ZoIX4zbeE9D2wHIrQSKIRgIqHRLXfe/VTl4D3+f16tG3A50W6sOA+NvZFjJquh6BNsJldcvJIvY2SK2bq4brA==",
    ),
    "omnirush-platform-arm64.tgz": (
        "https://registry.npmjs.org/@omnirush-ai/cli-linux-arm64/-/cli-linux-arm64-2.2.1.tgz",
        "sha512",
        "x4DHBV3cS/ajH+sn9T7GH/FUz6vLnDldmHI2uWh0cSWbfnuEFjJbHYOH6Dvt9qxbujl8jv0HRlkpxvtHJH8Mdg==",
    ),
    "omnirush-platform-x64.tgz": (
        "https://registry.npmjs.org/@omnirush-ai/cli-linux-x64/-/cli-linux-x64-2.2.1.tgz",
        "sha512",
        "8Fj6sLsf3cGHMVAFo/HeZwcXKYTwTppgdhp8zaIeAEJMxahEyPTR3h76yt7pQaQ74hpJ4MoY+HlH+YujIZtYuA==",
    ),
}


def expected_bytes(algorithm: str, value: str) -> bytes:
    return b64decode(value) if algorithm == "sha512" else bytes.fromhex(value)


def digest(path: Path, algorithm: str) -> bytes:
    hasher = sha512() if algorithm == "sha512" else sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.digest()


def fetch(name: str, url: str, algorithm: str, expected: str):
    target = ASSETS / name
    if target.exists() and digest(target, algorithm) == expected_bytes(algorithm, expected):
        print(f"cached {name}")
        return
    temporary = target.with_suffix(target.suffix + ".download")
    target.parent.mkdir(parents=True, exist_ok=True)
    print(f"fetching {name}")
    with urlopen(url, timeout=300) as response, temporary.open("wb") as output:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            output.write(chunk)
    if digest(temporary, algorithm) != expected_bytes(algorithm, expected):
        temporary.unlink(missing_ok=True)
        raise SystemExit(f"checksum mismatch for {name}")
    os.replace(temporary, target)


for filename, metadata in FILES.items():
    fetch(filename, *metadata)

print(f"runtime assets ready in {ASSETS}")

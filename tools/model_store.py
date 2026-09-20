#!/usr/bin/env python3
"""Install a pinned feasibility model bundle into a local directory."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import tempfile
import urllib.request
from pathlib import Path, PurePosixPath


class ManifestError(RuntimeError):
    pass


def install_bundle(manifest_path: Path, platform: str, destination_root: Path) -> Path:
    manifest = json.loads(manifest_path.read_text())
    try:
        bundle = manifest["platforms"][platform]
        model_id = manifest["id"]
        revision = bundle["revision"]
        files = bundle["files"]
    except (KeyError, TypeError) as error:
        raise ManifestError(f"invalid manifest: missing {error}") from error

    target = destination_root / model_id / platform
    target.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f"{platform}.staging-", dir=target.parent))

    try:
        for entry in files:
            relative_path = _safe_relative_path(entry["path"])
            output = staging / relative_path
            output.parent.mkdir(parents=True, exist_ok=True)
            _copy_and_verify(entry, output)

        (staging / "installation.json").write_text(
            json.dumps(
                {
                    "id": model_id,
                    "family": manifest["family"],
                    "platform": platform,
                    "revision": revision,
                },
                indent=2,
            )
            + "\n"
        )

        if target.exists():
            raise ManifestError(f"installation already exists: {target}")
        os.replace(staging, target)
        return target
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def _safe_relative_path(value: str) -> Path:
    path = PurePosixPath(value)
    if path.is_absolute() or not path.parts or ".." in path.parts or "." in path.parts:
        raise ManifestError(f"unsafe relative path: {value}")
    return Path(*path.parts)


def _copy_and_verify(entry: dict, output: Path) -> None:
    digest = hashlib.sha256()
    size = 0
    with urllib.request.urlopen(entry["url"]) as source, output.open("wb") as target:
        while chunk := source.read(1024 * 1024):
            target.write(chunk)
            digest.update(chunk)
            size += len(chunk)

    if size != entry["size"]:
        raise ManifestError(
            f"size mismatch for {entry['path']}: expected {entry['size']}, received {size}"
        )
    if digest.hexdigest() != entry["sha256"]:
        raise ManifestError(f"SHA-256 mismatch for {entry['path']}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("platform", choices=("ios", "android"))
    parser.add_argument("destination", type=Path)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path(__file__).parents[1] / "models" / "qwen2-0.5b-instruct.json",
    )
    arguments = parser.parse_args()
    print(install_bundle(arguments.manifest, arguments.platform, arguments.destination))


if __name__ == "__main__":
    main()

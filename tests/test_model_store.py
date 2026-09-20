import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from tools.model_store import ManifestError, install_bundle


class ModelStoreTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.source = self.root / "source"
        self.destination = self.root / "models"
        self.source.mkdir()

    def tearDown(self):
        self.temporary_directory.cleanup()

    def test_install_verifies_files_before_atomically_publishing_bundle(self):
        config = b'{"model_type":"qwen2"}\n'
        weights = b"synthetic model bytes"
        (self.source / "config.json").write_bytes(config)
        (self.source / "weights.bin").write_bytes(weights)

        manifest = self._write_manifest(
            [
                self._entry("config.json", config),
                self._entry("weights.bin", weights),
            ]
        )

        installed = install_bundle(manifest, "ios", self.destination)

        self.assertEqual(installed, self.destination / "qwen2-0.5b-instruct" / "ios")
        self.assertEqual((installed / "config.json").read_bytes(), config)
        self.assertEqual((installed / "weights.bin").read_bytes(), weights)
        receipt = json.loads((installed / "installation.json").read_text())
        self.assertEqual(receipt["revision"], "0123456789abcdef")
        self.assertFalse(any(self.destination.rglob("*.staging-*")))

    def test_checksum_failure_leaves_no_visible_or_staged_installation(self):
        expected = b"expected bytes"
        (self.source / "weights.bin").write_bytes(b"corruptedbytes")
        manifest = self._write_manifest([self._entry("weights.bin", expected)])

        with self.assertRaisesRegex(ManifestError, "SHA-256 mismatch"):
            install_bundle(manifest, "android", self.destination)

        self.assertFalse((self.destination / "qwen2-0.5b-instruct" / "android").exists())
        self.assertFalse(any(self.destination.rglob("*.staging-*")))

    def test_unsafe_destination_is_rejected_before_copying(self):
        payload = b"synthetic model bytes"
        (self.source / "weights.bin").write_bytes(payload)
        entry = self._entry("weights.bin", payload)
        entry["path"] = "../outside.bin"
        manifest = self._write_manifest([entry])

        with self.assertRaisesRegex(ManifestError, "unsafe relative path"):
            install_bundle(manifest, "android", self.destination)

        self.assertFalse((self.root / "outside.bin").exists())

    def _entry(self, name: str, content: bytes) -> dict:
        return {
            "path": name,
            "url": (self.source / name).as_uri(),
            "size": len(content),
            "sha256": hashlib.sha256(content).hexdigest(),
        }

    def _write_manifest(self, files: list[dict]) -> Path:
        manifest = {
            "id": "qwen2-0.5b-instruct",
            "family": "Qwen2",
            "platforms": {
                "ios": {"revision": "0123456789abcdef", "files": files},
                "android": {"revision": "0123456789abcdef", "files": files},
            },
        }
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest))
        return path


if __name__ == "__main__":
    unittest.main()

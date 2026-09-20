import subprocess
import tempfile
import unittest
from pathlib import Path


class PinIosPackagesTest(unittest.TestCase):
    def test_replaces_nativephp_ranges_with_exact_revisions(self):
        packages = {
            "https://github.com/ml-explore/mlx-swift-lm": "bd4b7434e6bdb588c7ef55706ff8904cb7fd4c57",
            "https://github.com/ml-explore/mlx-swift": "0bb916c67f4b9e5c682cbe02a42c701c93ab5021",
            "https://github.com/huggingface/swift-transformers": "c21fdcde390313a6d98d8e33a346f2c3486c3ab0",
        }
        blocks = "\n".join(
            f'''ABC{index:021d} /* package */ = {{
                isa = XCRemoteSwiftPackageReference;
                repositoryURL = "{url}";
                requirement = {{
                    kind = upToNextMajorVersion;
                    minimumVersion = 1.0.0;
                }};
            }};'''
            for index, url in enumerate(packages)
        )

        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory) / "project.pbxproj"
            project.write_text(blocks)
            result = subprocess.run(
                ["python3", "probe/pin-ios-packages.py", str(project)],
                text=True,
                capture_output=True,
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            pinned = project.read_text()
            self.assertNotIn("upToNextMajorVersion", pinned)
            for revision in packages.values():
                self.assertIn(f"revision = {revision};", pinned)


if __name__ == "__main__":
    unittest.main()

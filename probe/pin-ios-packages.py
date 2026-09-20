#!/usr/bin/env python3
import re
import sys
from pathlib import Path


PINS = {
    "https://github.com/ml-explore/mlx-swift-lm": "bd4b7434e6bdb588c7ef55706ff8904cb7fd4c57",
    "https://github.com/ml-explore/mlx-swift": "0bb916c67f4b9e5c682cbe02a42c701c93ab5021",
    "https://github.com/huggingface/swift-transformers": "c21fdcde390313a6d98d8e33a346f2c3486c3ab0",
}


def pin(project: Path) -> None:
    contents = project.read_text()
    for url, revision in PINS.items():
        pattern = re.compile(
            r"(isa = XCRemoteSwiftPackageReference;\s+repositoryURL = \""
            + re.escape(url)
            + r"\";\s+)requirement = \{.*?\};",
            re.DOTALL,
        )
        contents, count = pattern.subn(
            rf"\1requirement = {{\n\t\t\t\tkind = revision;\n\t\t\t\trevision = {revision};\n\t\t\t}};",
            contents,
        )
        if count != 1:
            raise SystemExit(f"expected one Swift package reference for {url}, found {count}")
    project.write_text(contents)


if __name__ == "__main__":
    pin(Path(sys.argv[1]))

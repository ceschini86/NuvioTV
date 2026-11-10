#!/usr/bin/env python3
"""Fork: resolve the version-line conflict every upstream sync hits in app/build.gradle.kts.

Upstream and the fork both bump versionCode/versionName, so merging an upstream release always
conflicts on those lines. A conflict block whose two sides hold nothing but those lines keeps the
fork's side (the sync bumps it right after). Any other conflict block is left for a human: the
script exits 1 and changes nothing.

    python3 scripts/fork_resolve_version_conflict.py app/build.gradle.kts
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

VERSION_LINE = re.compile(r"^\s*(versionCode|versionName)\s*=.*$")


def _only_version_lines(lines: list[str]) -> bool:
    content = [line for line in lines if line.strip()]
    return bool(content) and all(VERSION_LINE.match(line.rstrip("\r\n")) for line in content)


def resolve(text: str) -> str | None:
    """Returns the text with version-only conflicts resolved to ours, or None if another conflict remains."""
    out: list[str] = []
    lines = text.splitlines(keepends=True)
    i = 0
    resolved_any = False
    while i < len(lines):
        line = lines[i]
        if not line.startswith("<<<<<<< "):
            out.append(line)
            i += 1
            continue
        ours: list[str] = []
        theirs: list[str] = []
        i += 1
        while i < len(lines) and not lines[i].startswith(("=======", "|||||||")):
            ours.append(lines[i])
            i += 1
        if i < len(lines) and lines[i].startswith("|||||||"):  # diff3 base section
            while i < len(lines) and not lines[i].startswith("======="):
                i += 1
        i += 1  # skip =======
        while i < len(lines) and not lines[i].startswith(">>>>>>> "):
            theirs.append(lines[i])
            i += 1
        if i >= len(lines):
            return None  # unterminated block
        i += 1  # skip >>>>>>>
        if not (_only_version_lines(ours) and _only_version_lines(theirs)):
            return None
        out.extend(ours)
        resolved_any = True
    return "".join(out) if resolved_any else None


def main() -> int:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else "app/build.gradle.kts")
    text = path.read_text(encoding="utf-8")
    result = resolve(text)
    if result is None:
        print(f"{path}: conflicts other than versionCode/versionName; leaving them for a human", file=sys.stderr)
        return 1
    path.write_text(result, encoding="utf-8")
    print(f"{path}: kept the fork's versionCode/versionName")
    return 0


if __name__ == "__main__":
    sys.exit(main())

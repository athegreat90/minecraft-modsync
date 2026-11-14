#!/usr/bin/env python3
"""Verify that relative links and heading anchors between the repository's Markdown files resolve."""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SKIP = {"node_modules", "build", ".git", ".gradle", ".angular", "dist", "coverage", ".local", ".atl", "odd"}
LINK = re.compile(r"(?<!!)\[[^\]]*\]\(([^)\s]+)\)")
FENCE = re.compile(r"^(```|~~~)")


def markdown_files(root):
    for path in root.rglob("*.md"):
        if not SKIP.intersection(path.relative_to(root).parts):
            yield path


def slug(heading):
    """GitHub-style anchor for a heading: lower case, punctuation dropped, spaces become hyphens."""
    text = re.sub(r"[`*_]", "", heading.strip().lower())
    text = re.sub(r"[^\w\- ]", "", text)
    return text.replace(" ", "-")


def anchors(path):
    found = set()
    in_fence = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if FENCE.match(line):
            in_fence = not in_fence
        elif not in_fence and line.startswith("#"):
            found.add(slug(line.lstrip("#")))
    return found


def broken_links(root):
    problems = []
    for file in markdown_files(root):
        in_fence = False
        for number, line in enumerate(file.read_text(encoding="utf-8").splitlines(), 1):
            if FENCE.match(line):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            for target in LINK.findall(line):
                if re.match(r"[a-z][a-z0-9+.-]*:", target):
                    continue
                path_part, _, anchor = target.partition("#")
                resolved = file if not path_part else (file.parent / path_part).resolve()
                where = f"{file.relative_to(root)}:{number}"
                if not resolved.exists():
                    problems.append(f"{where}: missing file {target}")
                elif anchor and resolved.suffix == ".md" and anchor.lower() not in anchors(resolved):
                    problems.append(f"{where}: missing anchor {target}")
    return problems


def main():
    problems = broken_links(ROOT)
    for problem in problems:
        print(problem)
    if problems:
        raise SystemExit(f"{len(problems)} broken Markdown link(s)")
    print("Markdown links checked")


if __name__ == "__main__":
    sys.exit(main())

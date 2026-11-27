"""Linux equivalent of docs/check-doc-links.ps1: every relative link, image and <source> of README.md and docs/**/*.md must exist,
and the three architecture SVG documents must be well-formed XML. Comments are ignored, like in the PowerShell script.

    python3 scripts/check-doc-links.py [repository-root]
"""

import re
import sys
import xml.parsers.expat
from pathlib import Path
from urllib.parse import unquote

LINK = re.compile(r"\]\((?P<target>[^)\s]+)(?:\s+[^)]*)?\)")
IMAGE = re.compile(r'<(?:img|source)\b[^>]*?\b(?:src|srcset)\s*=\s*"(?P<sources>[^"]+)"', re.I | re.S)
EXTERNAL = re.compile(r"^(?:[a-z][a-z0-9+.-]*:|//)", re.I)


def main():
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent
    files = [root / "README.md"] + sorted((root / "docs").rglob("*.md"))
    failures = []
    for markdown in files:
        content = re.sub(r"<!--.*?-->", "", markdown.read_text(encoding="utf-8"), flags=re.S)
        targets = [m.group("target").strip("<>") for m in LINK.finditer(content)]
        for m in IMAGE.finditer(content):
            targets += [part.strip().split(None, 1)[0] for part in re.split(r"\s*,\s*", m.group("sources")) if part.strip()]
        for target in targets:
            if EXTERNAL.match(target) or target.startswith("#"):
                continue
            path = unquote(target.split("#", 1)[0])
            if path.strip() and not (markdown.parent / path).resolve().exists():
                failures.append(f"{markdown.relative_to(root)}: missing link target '{path}'")
    for name in ("architecture-overview.svg", "request-flow-and-data.svg", "agent-integration.svg"):
        try:
            parser = xml.parsers.expat.ParserCreate()
            parser.Parse((root / "docs" / "images" / name).read_bytes(), True)
        except (xml.parsers.expat.ExpatError, OSError) as error:
            failures.append(f"docs/images/{name}: invalid XML: {error}")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        sys.exit(1)
    print(f"Checked {len(files)} Markdown files and 3 SVG documents; no missing relative links, images or XML errors.")


if __name__ == "__main__":
    main()

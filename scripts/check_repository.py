#!/usr/bin/env python3
"""Check community files, local Markdown links, and release metadata; stdlib only."""
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
IGNORED = {".git", "target", "build", ".idea", ".vscode"}
REQUIRED = (
    "README.md", ".github/CONTRIBUTING.md", ".github/CODE_OF_CONDUCT.md", ".github/SECURITY.md",
    ".github/SUPPORT.md", "LICENSE", "CHANGELOG.md", ".github/CODEOWNERS",
    ".github/PULL_REQUEST_TEMPLATE.md", ".github/ISSUE_TEMPLATE/config.yml",
    "docs/README.md", "docs/architecture.md", "docs/maintaining.md",
)


def main():
    errors = []
    for name in REQUIRED:
        if not (ROOT / name).is_file() or not (ROOT / name).read_text(encoding="utf-8").strip():
            errors.append(f"Missing or empty required file: {name}")
    count = 0
    for path in ROOT.rglob("*.md"):
        if IGNORED.intersection(path.relative_to(ROOT).parts):
            continue
        count += 1
        text = path.read_text(encoding="utf-8")
        # Ignore code fences: commands and example Markdown are not repository links.
        text = re.sub(r"```.*?```", "", text, flags=re.S)
        for match in re.finditer(r"!?\[[^\]]*\]\(([^)]+)\)", text):
            target = match.group(1).strip().split(' "', 1)[0].strip("<>")
            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc or not parsed.path:
                continue
            destination = (path.parent / unquote(parsed.path)).resolve()
            if not destination.is_relative_to(ROOT) or not destination.exists():
                errors.append(f"Broken local link in {path.relative_to(ROOT)}: {target}")
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom = ET.parse(ROOT / "pom.xml").getroot()
    version = pom.findtext("m:version", namespaces=ns)
    changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    if not version or not re.search(r"^## " + re.escape(version) + r"(?:\s|$)", changelog, re.M):
        errors.append(f"No changelog heading for POM version {version!r}")
    if pom.findall("m:dependencies/m:dependency", ns):
        errors.append("Project dependencies need explicit review: zero runtime dependencies is a project boundary.")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Repository checks passed: {count} Markdown files, local file links, community files, version {version}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

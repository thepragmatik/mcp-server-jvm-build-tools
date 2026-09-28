#!/usr/bin/env python3
"""Fail a Pages build that lacks a usable root page."""

from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit


class Links(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.hrefs: set[str] = set()

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "a":
            self.hrefs.update(value for key, value in attrs if key == "href" and value)


def main() -> None:
    site = Path("site")
    index = site / "index.html"
    if not index.is_file() or index.stat().st_size == 0:
        raise SystemExit("Pages root is missing or empty")

    links = Links()
    links.feed(index.read_text(encoding="utf-8"))
    required = {
        "user-guide/quickstart-v2/",
        "user-guide/migration-v2/",
        "reference/architecture/",
        "reference/tool-catalog/",
    }
    found = {urlsplit(href).path.lstrip("/") for href in links.hrefs}
    missing = required - found
    if missing:
        raise SystemExit(f"Pages root is missing key routes: {', '.join(sorted(missing))}")
    for route in required:
        target = site / route / "index.html"
        if not target.is_file() or target.stat().st_size == 0:
            raise SystemExit(f"Pages route is missing or empty: {route}")
    print("Pages root and key routes are present")


if __name__ == "__main__":
    main()

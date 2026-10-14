"""Check release/HTML consistency and local assets before publishing. Standard library only."""
from html.parser import HTMLParser
from pathlib import Path
import json
import re
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / "site"
REPO = "https://github.com/soBigRice/sms-ferry"


class Page(HTMLParser):
    def __init__(self):
        super().__init__()
        self.tags = []
        self.ids = set()

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        self.tags.append((tag, attrs))
        if "id" in attrs:
            assert attrs["id"] not in self.ids, f"Duplicate ID: {attrs['id']}"
            self.ids.add(attrs["id"])


release = json.loads((SITE / "release.json").read_text())
version = release["version"]
assert re.fullmatch(r"\d+\.\d+\.\d+", version), "Invalid release version"
assert re.fullmatch(r"[a-f0-9]{64}", release["sha256"]), "Invalid SHA-256"
assert isinstance(release["bytes"], int) and release["bytes"] > 0, "Invalid APK size"
assert release["apkUrl"] == f"{REPO}/releases/download/v{version}/sms-ferry-{version}-debug.apk"
assert release["releaseUrl"] == f"{REPO}/releases/tag/v{version}"
for filename, language, preview in [("index.html", "zh-CN", "预览版"), ("en.html", "en", "Preview")]:
    html = (SITE / filename).read_text()
    page = Page()
    page.feed(html)
    assert f'<html lang="{language}">' in html
    assert html.count(release["sha256"]) == 1, f"Update the no-JS checksum in {filename}"
    assert f"{version} {preview} · Android {release['minAndroid']}+ · {release['bytes']/1_000_000:.1f} MB" in html
    assert len([tag for tag, attrs in page.tags if tag == "h1"]) == 1
    downloads = [attrs for tag, attrs in page.tags if tag == "a" and "data-download" in attrs]
    assert len(downloads) == 2 and all(a["href"] == release["apkUrl"] for a in downloads), f"Update both no-JS download links in {filename}"
    languages = [attrs for tag, attrs in page.tags if "data-language" in attrs]
    assert {a["data-language"] for a in languages} == {"zh", "en"}
    assert [a["data-language"] for a in languages if a.get("aria-current") == "page"] == [language[:2]]
    for tag, attrs in page.tags:
        if tag == "img":
            assert "alt" in attrs and "width" in attrs and "height" in attrs, "Images need alt and dimensions"
        if attrs.get("target") == "_blank":
            assert {"noopener", "noreferrer"} <= set(attrs.get("rel", "").split()), "Missing external link protection"
        for key in ["src", "href"]:
            value = attrs.get(key, "")
            if not value:
                continue
            url = urlsplit(value)
            if url.scheme or url.netloc:
                continue
            assert not url.path.startswith("/"), f"Project Pages needs relative paths: {value}"
            if url.fragment:
                assert url.fragment in page.ids, f"Broken local anchor: {value}"
            if url.path:
                target = (SITE / unquote(url.path)).resolve()
                assert target.is_relative_to(SITE.resolve()) and target.exists(), f"Missing asset: {value}"
assert (SITE / "assets/icons/LICENSE").is_file(), "Missing vendored icon license"
print(f"Website check passed: v{version}, Chinese + English, 4 download links, all local assets and anchors present")

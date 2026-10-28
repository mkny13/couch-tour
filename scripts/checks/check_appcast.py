#!/usr/bin/env python3
"""
Inspect, validate, and repair Sparkle appcast RSS feeds (issue #781).

Subcommands:
  check FILE...
    Verifies that no two <item> elements in a feed share a sparkle:shortVersionString.
    Returns 1 if duplicates are found, listing offenders.

  drop-version FILE VERSION
    Removes every item whose shortVersionString equals VERSION, writing the file
    in place textually so the rest of the feed remains byte-identical.

  repair FILE --repo OWNER/REPO
    For any duplicated shortVersionString, queries GitHub Releases for the asset size,
    identifies the single item whose enclosure length matches the asset size,
    verifies its sparkle:edSignature against macos/project.yml using openssl,
    and removes the mismatched duplicate item(s).
    If no item or multiple items match, exits 1 without modifying the file.
"""

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

SPARKLE_NS = "http://www.andymatuschak.org/xml-namespaces/sparkle"
ITEM_PATTERN = re.compile(r"([ \t]*<item[\s>].*?</item>[ \t]*\r?\n?)", re.DOTALL)
NS_WRAPPER = f'<rss xmlns:sparkle="{SPARKLE_NS}" xmlns:dc="http://purl.org/dc/elements/1.1/">'


class AppcastItem:
    def __init__(self, raw_snippet, span, version, length, url, signature, title):
        self.raw_snippet = raw_snippet
        self.span = span  # (start, end) in file text
        self.version = version
        self.length = length
        self.url = url
        self.signature = signature
        self.title = title


def parse_items(content):
    items = []
    for match in ITEM_PATTERN.finditer(content):
        raw = match.group(0)
        span = match.span()
        try:
            root = ET.fromstring(f"{NS_WRAPPER}{raw.strip()}</rss>")
        except ET.ParseError as e:
            print(f"Warning: failed to parse XML item snippet: {e}", file=sys.stderr)
            continue

        item_elem = root.find("item")
        if item_elem is None:
            continue

        # Extract title if present
        title_elem = item_elem.find("title")
        title = title_elem.text.strip() if title_elem is not None and title_elem.text else ""

        # Extract sparkle:shortVersionString from child or enclosure attribute
        version = None
        ver_elem = item_elem.find(f"{{{SPARKLE_NS}}}shortVersionString")
        if ver_elem is not None and ver_elem.text and ver_elem.text.strip():
            version = ver_elem.text.strip()

        enclosure = item_elem.find("enclosure")
        url = None
        length = None
        signature = None

        if enclosure is not None:
            if not version:
                version = enclosure.attrib.get(f"{{{SPARKLE_NS}}}shortVersionString") or enclosure.attrib.get("sparkle:shortVersionString")
                if version:
                    version = version.strip()

            url = enclosure.attrib.get("url")
            len_str = enclosure.attrib.get("length")
            if len_str and len_str.isdigit():
                length = int(len_str)

            signature = enclosure.attrib.get(f"{{{SPARKLE_NS}}}edSignature") or enclosure.attrib.get("sparkle:edSignature")
            if signature:
                signature = signature.strip()

        items.append(AppcastItem(raw, span, version, length, url, signature, title))
    return items


def find_project_yml():
    here = os.path.dirname(os.path.abspath(__file__))
    candidates = [
        os.path.join(here, "..", "..", "macos", "project.yml"),
        os.path.join(os.getcwd(), "macos", "project.yml"),
    ]
    for c in candidates:
        abs_c = os.path.abspath(c)
        if os.path.isfile(abs_c):
            return abs_c
    return None


def get_public_ed_key():
    project_yml = find_project_yml()
    if not project_yml:
        raise RuntimeError("Could not locate macos/project.yml to extract SUPublicEDKey")
    with open(project_yml, "r", encoding="utf-8") as f:
        content = f.read()
    m = re.search(r'SUPublicEDKey:\s*"([^"]+)"', content)
    if not m:
        raise RuntimeError(f"SUPublicEDKey not found in {project_yml}")
    return m.group(1).strip()


def remove_spans(content, spans_to_remove):
    sorted_spans = sorted(spans_to_remove, key=lambda s: s[0], reverse=True)
    for start, end in sorted_spans:
        content = content[:start] + content[end:]
    return content


def cmd_check(args):
    has_errors = False
    for path in args.files:
        if not os.path.isfile(path):
            print(f"ERROR: File not found: {path}", file=sys.stderr)
            has_errors = True
            continue

        with open(path, "r", encoding="utf-8") as f:
            content = f.read()

        items = parse_items(content)
        version_counts = {}
        for it in items:
            if it.version:
                version_counts[it.version] = version_counts.get(it.version, 0) + 1

        duplicates = {ver: count for ver, count in version_counts.items() if count > 1}
        if duplicates:
            has_errors = True
            for ver, count in sorted(duplicates.items()):
                print(f"ERROR: {path} contains duplicate shortVersionString: {ver} ({count} items)", file=sys.stderr)

    sys.exit(1 if has_errors else 0)


def cmd_drop_version(args):
    path = args.file
    version_to_drop = args.version

    if not os.path.isfile(path):
        print(f"ERROR: File not found: {path}", file=sys.stderr)
        sys.exit(1)

    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    items = parse_items(content)
    spans_to_remove = [it.span for it in items if it.version == version_to_drop]

    if not spans_to_remove:
        print(f"Version {version_to_drop} not found in {path}; no changes made.")
        sys.exit(0)

    new_content = remove_spans(content, spans_to_remove)
    with open(path, "w", encoding="utf-8") as f:
        f.write(new_content)

    print(f"Removed {len(spans_to_remove)} item(s) for version {version_to_drop} from {path}.")
    sys.exit(0)


def cmd_repair(args):
    path = args.file
    repo = args.repo

    if not os.path.isfile(path):
        print(f"ERROR: File not found: {path}", file=sys.stderr)
        sys.exit(1)

    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    items = parse_items(content)
    version_items = {}
    for it in items:
        if it.version:
            version_items.setdefault(it.version, []).append(it)

    duplicates = {ver: its for ver, its in version_items.items() if len(its) > 1}
    if not duplicates:
        print(f"No duplicate versions found in {path}.")
        sys.exit(0)

    spans_to_remove = []
    b64_pubkey = None

    for ver, dup_list in sorted(duplicates.items()):
        print(f"Analyzing duplicate version {ver} ({len(dup_list)} items)...")

        # Derive tag and asset name from enclosure URL
        # e.g. https://github.com/mkny13/couch-tour/releases/download/v0.99/CouchTour-v0.99.zip
        first_url = None
        for it in dup_list:
            if it.url:
                first_url = it.url
                break

        if not first_url:
            print(f"ERROR: No enclosure URL found for duplicate version {ver}", file=sys.stderr)
            sys.exit(1)

        m = re.search(r"/releases/download/([^/]+)/([^/]+)$", first_url)
        if not m:
            print(f"ERROR: Cannot parse tag and asset name from enclosure URL: {first_url}", file=sys.stderr)
            sys.exit(1)

        tag = m.group(1)
        asset_name = m.group(2)

        # Query gh api for release metadata
        api_cmd = ["gh", "api", f"repos/{repo}/releases/tags/{tag}"]
        res = subprocess.run(api_cmd, capture_output=True, text=True)
        if res.returncode != 0:
            print(f"ERROR: 'gh api' failed for tag {tag}: {res.stderr.strip()}", file=sys.stderr)
            sys.exit(1)

        try:
            rel_data = json.loads(res.stdout)
        except json.JSONDecodeError as e:
            print(f"ERROR: Failed to parse GitHub API JSON for tag {tag}: {e}", file=sys.stderr)
            sys.exit(1)

        assets = rel_data.get("assets", [])
        matched_asset = None
        for a in assets:
            if a.get("name") == asset_name:
                matched_asset = a
                break

        if not matched_asset:
            print(f"ERROR: Asset {asset_name} not found in release {tag} on {repo}", file=sys.stderr)
            sys.exit(1)

        asset_size = matched_asset.get("size")
        if asset_size is None:
            print(f"ERROR: Asset {asset_name} in release {tag} has no size field", file=sys.stderr)
            sys.exit(1)

        # Match items by enclosure length
        matching_items = [it for it in dup_list if it.length == asset_size]
        if len(matching_items) != 1:
            print(
                f"ERROR: Expected exactly 1 item matching asset size {asset_size} for version {ver}, "
                f"found {len(matching_items)} (items lengths: {[it.length for it in dup_list]})",
                file=sys.stderr,
            )
            sys.exit(1)

        chosen_item = matching_items[0]
        if not chosen_item.signature:
            print(f"ERROR: Matching item for version {ver} has no sparkle:edSignature", file=sys.stderr)
            sys.exit(1)

        # Verify signature against SUPublicEDKey
        if b64_pubkey is None:
            b64_pubkey = get_public_ed_key()

        der_prefix = bytes.fromhex("302a300506032b6570032100")
        der_key = der_prefix + base64.b64decode(b64_pubkey)
        raw_sig = base64.b64decode(chosen_item.signature)

        with tempfile.TemporaryDirectory() as td:
            key_file = os.path.join(td, "pub.der")
            sig_file = os.path.join(td, "sig.bin")
            with open(key_file, "wb") as f:
                f.write(der_key)
            with open(sig_file, "wb") as f:
                f.write(raw_sig)

            # Download asset using gh release download
            dl_cmd = ["gh", "release", "download", tag, "--pattern", asset_name, "--repo", repo, "--dir", td, "--clobber"]
            dl_res = subprocess.run(dl_cmd, capture_output=True, text=True)
            if dl_res.returncode != 0:
                print(f"ERROR: Failed to download asset {asset_name} for tag {tag}: {dl_res.stderr.strip()}", file=sys.stderr)
                sys.exit(1)

            asset_file = os.path.join(td, asset_name)
            if not os.path.isfile(asset_file):
                print(f"ERROR: Downloaded asset file not found at {asset_file}", file=sys.stderr)
                sys.exit(1)

            # Verify with openssl
            verify_cmd = [
                "openssl",
                "pkeyutl",
                "-verify",
                "-pubin",
                "-rawin",
                "-keyform",
                "DER",
                "-inkey",
                key_file,
                "-in",
                asset_file,
                "-sigfile",
                sig_file,
            ]
            v_res = subprocess.run(verify_cmd, capture_output=True, text=True)
            if v_res.returncode != 0:
                print(f"ERROR: OpenSSL signature verification failed for version {ver}: {v_res.stderr.strip()}", file=sys.stderr)
                sys.exit(1)

        # Item verified! Mark all other duplicate items for version `ver` for removal
        for it in dup_list:
            if it is not chosen_item:
                spans_to_remove.append(it.span)

    # Perform textual removal
    new_content = remove_spans(content, spans_to_remove)
    with open(path, "w", encoding="utf-8") as f:
        f.write(new_content)

    print(f"Successfully repaired {path}: removed {len(spans_to_remove)} duplicate item(s).")
    sys.exit(0)


def main():
    parser = argparse.ArgumentParser(description="Check and repair Sparkle appcasts")
    subparsers = parser.add_subparsers(dest="command", required=True)

    check_parser = subparsers.add_parser("check", help="Check feeds for duplicate versions")
    check_parser.add_argument("files", nargs="+", help="Feed files to check")
    check_parser.set_defaults(func=cmd_check)

    drop_parser = subparsers.add_parser("drop-version", help="Remove all items of a specific version")
    drop_parser.add_argument("file", help="Feed file to modify")
    drop_parser.add_argument("version", help="Version string to remove (e.g. 0.99)")
    drop_parser.set_defaults(func=cmd_drop_version)

    repair_parser = subparsers.add_parser("repair", help="Repair feeds by keeping only matching release asset item")
    repair_parser.add_argument("file", help="Feed file to repair")
    repair_parser.add_argument("--repo", default="mkny13/couch-tour", help="GitHub repo (e.g. mkny13/couch-tour)")
    repair_parser.set_defaults(func=cmd_repair)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()

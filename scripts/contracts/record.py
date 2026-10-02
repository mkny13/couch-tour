#!/usr/bin/env python3
"""Records real phish.in and Relisten responses as contract fixtures (see DECISIONS.md).

Hits the endpoints the clients actually call (Api.kt, Relisten.kt), trims every JSON array to
its first 3 elements so fixtures stay small while values and shape stay real, and writes the
same bytes to both the Android and CouchTourKit fixture dirs (macos/scripts/check-fixtures.sh
requires them to match). Polite by design: ~11 sequential requests with a pause between.

    scripts/contracts/record.sh [--out-dir DIR]
"""
import argparse
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

USER_AGENT = "CouchTour-contract-recorder (+https://github.com/mkny13/couch-tour)"
PHISHIN = "https://phish.in/api/v2"
RELISTEN = "https://api.relisten.net/api"
PAUSE_SECONDS = 1.0
MAX_ARRAY = 3
FIXTURE_YEAR = "1997"
FIXTURE_DATE = "1997-11-22"

REPO = Path(__file__).resolve().parents[2]
DEFAULT_OUT_DIRS = [
    REPO / "app/src/test/resources/fixtures",
    REPO / "macos/Packages/CouchTourKit/Tests/CouchTourKitTests/Fixtures",
]

_last_request = 0.0


def fetch(url):
    """GET [url] and parse it as JSON, pausing so requests stay sequential and spaced out."""
    global _last_request
    wait = _last_request + PAUSE_SECONDS - time.monotonic()
    if wait > 0:
        time.sleep(wait)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    print(f"GET {url}", file=sys.stderr)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.load(resp)
    finally:
        _last_request = time.monotonic()


def trim(value):
    """Cut every array to its first MAX_ARRAY elements, recursively."""
    if isinstance(value, list):
        return [trim(v) for v in value[:MAX_ARRAY]]
    if isinstance(value, dict):
        return {k: trim(v) for k, v in value.items()}
    return value


def q(params):
    return urllib.parse.urlencode(params)


def endpoints():
    """Yields (output name, response) in request order; Relisten uuids are resolved live."""
    yield "contract_phishin_years.json", fetch(f"{PHISHIN}/years")
    yield "contract_phishin_shows_year.json", fetch(
        f"{PHISHIN}/shows?" + q({"year": FIXTURE_YEAR, "audio_status": "complete_or_partial",
                                 "sort": "date:asc", "per_page": 1000}))
    yield "contract_phishin_show.json", fetch(f"{PHISHIN}/shows/{FIXTURE_DATE}")
    yield "contract_phishin_search.json", fetch(
        f"{PHISHIN}/search/tweezer?" + q({"audio_status": "complete_or_partial"}))
    yield "contract_phishin_playlists.json", fetch(
        f"{PHISHIN}/playlists?" + q({"sort": "likes_count:desc", "per_page": 100}))

    artists = fetch(f"{RELISTEN}/v3/artists")
    yield "contract_relisten_artists.json", artists
    phish = next(a for a in artists if a["slug"] == "phish")["uuid"]

    years = fetch(f"{RELISTEN}/v3/artists/{phish}/years")
    yield "contract_relisten_years.json", years
    year = next(y for y in years if y["year"] == FIXTURE_YEAR)["uuid"]

    yield "contract_relisten_year.json", fetch(f"{RELISTEN}/v3/artists/{phish}/years/{year}")
    yield "contract_relisten_show.json", fetch(f"{RELISTEN}/v2/artists/phish/shows/{FIXTURE_DATE}")
    yield "contract_relisten_on_date.json", fetch(
        f"{RELISTEN}/v2/artists/phish/shows/on-date?" + q({"month": 11, "day": 22}))
    yield "contract_relisten_search.json", fetch(f"{RELISTEN}/v3/search?" + q({"q": "tweezer"}))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--out-dir", type=Path, action="append",
                        help="write here instead of both repo fixture dirs (repeatable)")
    args = parser.parse_args()
    out_dirs = args.out_dir or DEFAULT_OUT_DIRS
    for d in out_dirs:
        d.mkdir(parents=True, exist_ok=True)

    # Fetch everything before writing anything, so a mid-run failure can't leave the two
    # fixture dirs half re-recorded.
    recorded = [(name, json.dumps(trim(body), indent=2, sort_keys=True, ensure_ascii=False) + "\n")
                for name, body in endpoints()]
    for name, text in recorded:
        for d in out_dirs:
            (d / name).write_text(text, encoding="utf-8")
    print(f"Recorded {len(recorded)} fixtures to {', '.join(str(d) for d in out_dirs)}", file=sys.stderr)


if __name__ == "__main__":
    main()

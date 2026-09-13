#!/usr/bin/env python3
"""Uploads the store listing's images to Google Play.

    PLAY_SERVICE_ACCOUNT_JSON=... python3 scripts/ci/upload-play-listing.py [--dry-run]

The bundle upload and this are different APIs doing different things, which is why the release
workflow cannot do it: `upload-google-play` publishes an artifact to a track, and the artwork on the
store page is a property of the *listing*, edited through `edits.images`. Until now that meant
someone dragging four PNGs into a browser after every UI change, which is exactly the step that gets
skipped — the listing on Play showed screens the app had stopped looking like two versions ago.

Images of a type are replaced wholesale, not appended: Play keeps up to eight phone screenshots and
uploading four more without clearing would leave the old four sitting beside the new ones. The
`deleteall` below is therefore deliberate and is the destructive part of this script.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

import requests
from google.oauth2 import service_account
from google.auth.transport.requests import Request

BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def token() -> str:
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "").strip()
    if not raw:
        raise SystemExit("PLAY_SERVICE_ACCOUNT_JSON is empty — nothing to authenticate with")
    credentials = service_account.Credentials.from_service_account_info(
        json.loads(raw), scopes=[SCOPE],
    )
    credentials.refresh(Request())
    return credentials.token


class Play:
    def __init__(self, package: str, dry_run: bool) -> None:
        self.package = package
        self.dry_run = dry_run
        self.session = requests.Session()
        self.session.headers["Authorization"] = f"Bearer {token()}"
        self.edit: str | None = None

    def _check(self, response: requests.Response, what: str) -> dict:
        if not response.ok:
            # The API's own message is the only useful diagnosis here — a service account that can
            # upload bundles but has not been granted "Store presence" comes back as a bare 403,
            # and guessing at that from the outside wastes an afternoon.
            raise SystemExit(f"{what} failed: HTTP {response.status_code}\n{response.text}")
        return response.json() if response.content else {}

    def open_edit(self) -> None:
        self.edit = self._check(
            self.session.post(f"{BASE}/{self.package}/edits"), "creating an edit",
        )["id"]
        print(f"edit {self.edit} opened")

    def languages(self) -> list[str]:
        listings = self._check(
            self.session.get(f"{BASE}/{self.package}/edits/{self.edit}/listings"),
            "listing the store listings",
        )
        return [entry["language"] for entry in listings.get("listings", [])]

    def existing(self, language: str, image_type: str) -> int:
        images = self._check(
            self.session.get(
                f"{BASE}/{self.package}/edits/{self.edit}/listings/{language}/{image_type}",
            ),
            f"reading {image_type}",
        )
        return len(images.get("images", []))

    def replace(self, language: str, image_type: str, files: list[Path]) -> None:
        present = self.existing(language, image_type)
        print(f"{image_type}: {present} on the listing, uploading {len(files)}")
        for file in files:
            print(f"  {file.name} ({file.stat().st_size // 1024} KB)")
        if self.dry_run:
            return

        self._check(
            self.session.delete(
                f"{BASE}/{self.package}/edits/{self.edit}/listings/{language}/{image_type}",
            ),
            f"clearing {image_type}",
        )
        for file in files:
            self._check(
                self.session.post(
                    f"{UPLOAD}/{self.package}/edits/{self.edit}/listings/{language}/{image_type}",
                    params={"uploadType": "media"},
                    headers={"Content-Type": "image/png"},
                    data=file.read_bytes(),
                ),
                f"uploading {file.name}",
            )

    def commit(self) -> None:
        if self.dry_run:
            print("dry run: the edit is abandoned rather than committed")
            return
        self._check(
            self.session.post(f"{BASE}/{self.package}/edits/{self.edit}:commit"),
            "committing the edit",
        )
        print("committed — the listing now shows these images")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", default="com.athkar.mobile")
    parser.add_argument("--language", default="ar")
    parser.add_argument("--screenshots", type=Path, default=Path("store/screenshots"))
    parser.add_argument("--icon", type=Path, default=Path("store/play-icon-512.png"))
    parser.add_argument("--feature", type=Path, default=Path("store/play-feature-graphic.png"))
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="say what would be replaced, then abandon the edit",
    )
    args = parser.parse_args()

    shots = sorted(p for p in args.screenshots.glob("*.png"))
    if len(shots) < 2:
        raise SystemExit(f"Play requires at least two phone screenshots; found {len(shots)}")
    for required in (args.icon, args.feature):
        if not required.is_file():
            raise SystemExit(f"missing {required}")

    play = Play(args.package, args.dry_run)
    play.open_edit()

    available = play.languages()
    if args.language not in available:
        # Uploading to a language with no listing creates a half-made one, which on Play is a
        # visible, empty store page in that language rather than a silent no-op.
        raise SystemExit(
            f"no '{args.language}' listing on this app; it has: {', '.join(available) or 'none'}"
        )

    play.replace(args.language, "phoneScreenshots", shots)
    play.replace(args.language, "icon", [args.icon])
    play.replace(args.language, "featureGraphic", [args.feature])
    play.commit()


if __name__ == "__main__":
    sys.exit(main())

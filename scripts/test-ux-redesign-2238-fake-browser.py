#!/usr/bin/env python3
"""Tiny deterministic browser stand-in for the #2260 smoke-harness self-test."""

import json
import os
import re
import sys
import time
from urllib.parse import urlencode, urlparse
from urllib.request import urlopen


def main() -> int:
    startup_delay = float(os.environ.get("POCKETSHELL_FAKE_BROWSER_STARTUP_SECONDS", "0"))
    result_delay = float(os.environ.get("POCKETSHELL_FAKE_BROWSER_RESULT_DELAY_SECONDS", "0"))
    mode = os.environ.get("POCKETSHELL_FAKE_BROWSER_MODE", "report")
    time.sleep(startup_delay)

    page_url = sys.argv[-1]
    with urlopen(page_url, timeout=10) as response:
        page = response.read().decode("utf-8")

    match = re.search(r"const token = (\".*?\");", page)
    if match is None:
        raise RuntimeError("smoke observer token was not injected")
    token = json.loads(match.group(1))

    if mode == "missing":
        while True:
            time.sleep(1)

    time.sleep(result_delay)
    result_url = f"{urlparse(page_url)._replace(query='', fragment='').geturl()}"
    result_url = result_url.rsplit("/ux-redesign-2238.html", 1)[0]
    result_url += "/__pocketshell_smoke_result?" + urlencode(
        {"token": token, "value": "pass", "text": "fake browser"}
    )
    with urlopen(result_url, timeout=10):
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

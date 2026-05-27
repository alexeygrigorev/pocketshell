"""Allow `python -m pocketshell` as an alternative to the installed entry point.

Used in CI and developer environments where the console-script shim from
`pip install -e .` / `uv tool install pocketshell-cli` is not on PATH.
"""

from __future__ import annotations

import sys

from pocketshell.cli import main

if __name__ == "__main__":
    sys.exit(main())

"""Is this copy of the SamRabbit Mac bridge the installed one?

Only the installed copy may reach the real T3 Code, Google Calendar and Heptabase journal by default. Every other
copy (a checkout, a test, a dev bridge on another port, a copy installed into a temp HOME) is a *dev copy*: its
Heptabase CLI is a dry run, its T3 answers ``t3_dev_copy`` and its calendar ``calendar_dev_copy``, unless it is given
an explicit CLI path, T3 URL or Composio CLI.

``install.sh`` copies the bridge to ``~/Library/Application Support/SamRabbit/bridge`` and writes a marker there,
``.samrabbit-installed``, holding that folder's path. A copy is the installed one only when all of these hold:

* it runs from ``<home>/Library/Application Support/SamRabbit/bridge``, where ``<home>`` is the user's home from the
  password database (``pwd.getpwuid(os.getuid()).pw_dir``), never ``$HOME``: a test that points HOME at a temp
  folder and installs a copy there never gets the installed copy;
* ``$HOME``, when it is set, is that same home (anything run with a temp HOME is a dev run);
* the marker is a regular file (not a link) owned by this user that names exactly this folder (a copy of the
  installed folder somewhere else carries a marker naming another folder).

Command line (install.sh prints it)::

    python3 -I samrabbit_installed.py [<folder>]   ->  "installed" or "dev (<reason>)"

Stdlib only, Python 3.9.
"""

from __future__ import annotations

import os
import pwd
import stat
import sys
from typing import Mapping, Optional

_HERE = os.path.dirname(os.path.abspath(__file__))

MARKER_NAME = ".samrabbit-installed"
INSTALLED_PARTS = ("Library", "Application Support", "SamRabbit", "bridge")
MAX_MARKER_BYTES = 4096

# Why a copy is a dev copy (``dev_reason``).
REASON_NO_ACCOUNT = "no_account_home"
REASON_ELSEWHERE = "not_the_install_folder"
REASON_HOME = "home_is_not_the_account_home"
REASON_NO_MARKER = "no_install_marker"
REASON_MISMATCH = "marker_names_another_folder"


def account_home() -> Optional[str]:
    """This user's home from the password database (not ``$HOME``), or None."""
    try:
        home = pwd.getpwuid(os.getuid()).pw_dir
    except (KeyError, OSError):
        return None
    return home if isinstance(home, str) and os.path.isabs(home) else None


def installed_dir(home: Optional[str] = None) -> Optional[str]:
    """``<home>/Library/Application Support/SamRabbit/bridge`` for ``home`` (default: ``account_home()``)."""
    base = home if home is not None else account_home()
    return os.path.join(base, *INSTALLED_PARTS) if base else None


def read_marker(folder: str) -> Optional[str]:
    """The folder named by ``<folder>/.samrabbit-installed``: a regular file (never followed when it is a link)
    owned by this user, at most 4 KiB; None otherwise."""
    path = os.path.join(folder, MARKER_NAME)
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0))
    except OSError:
        return None
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_size > MAX_MARKER_BYTES:
            return None
        data = os.read(descriptor, MAX_MARKER_BYTES + 1)
    except OSError:
        return None
    finally:
        os.close(descriptor)
    try:
        lines = data.decode("utf-8").splitlines()
    except UnicodeDecodeError:
        return None
    named = lines[0].strip() if lines else ""
    return named if os.path.isabs(named) else None


def dev_reason(here: Optional[str] = None, *, home: Optional[str] = None,
               environ: Optional[Mapping[str, str]] = None) -> Optional[str]:
    """None for the installed copy, else why ``here`` (default: this module's folder) is a dev copy. ``home`` stands
    in for the account home and ``environ`` for ``os.environ`` (tests of the decision itself only)."""
    owner = account_home() if home is None else home
    expected = installed_dir(owner) if owner else None
    if not owner or not expected:
        return REASON_NO_ACCOUNT
    location = os.path.realpath(here or _HERE)
    if location != os.path.realpath(expected):
        return REASON_ELSEWHERE
    env_home = (os.environ if environ is None else environ).get("HOME")
    if env_home and os.path.realpath(env_home) != os.path.realpath(owner):
        return REASON_HOME
    named = read_marker(location)
    if named is None:
        return REASON_NO_MARKER
    if os.path.realpath(named) != location:
        return REASON_MISMATCH
    return None


def is_installed_copy(here: Optional[str] = None, *, home: Optional[str] = None,
                      environ: Optional[Mapping[str, str]] = None) -> bool:
    """True only for the copy install.sh put in this user's own Library (see the module docstring)."""
    return dev_reason(here, home=home, environ=environ) is None


def describe(here: Optional[str] = None) -> str:
    reason = dev_reason(here)
    return "installed" if reason is None else f"dev ({reason})"


def main(argv: Optional[list] = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    print(describe(args[0] if args else None))
    return 0


if __name__ == "__main__":
    sys.exit(main())

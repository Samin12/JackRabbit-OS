"""The user's Google account for links opened in Chrome on the Mac (``mac_open``).

Chrome opens a bare https://calendar.google.com in the default signed-in account (``/u/0``), which may not be the
account the user works in. ``mac_open`` therefore pins Google Calendar, Gmail, Drive, Docs and Meet links to the
user's account with ``authuser=<email>``, unless the link already names one.

The account is a non-secret setting (``provider_settings`` row ``mac.google_account``, set on the management
page's Mac card). Without it, it comes from the calendar connection: the label or calendar name of a calendar
account when that is an email address (a Google secret iCal feed is named after its account, e.g.
``samin@aianswer.us``).
"""

from __future__ import annotations

from datetime import UTC, datetime
import re
import sqlite3
from urllib.parse import parse_qsl, quote, urlsplit, urlunsplit

from sam_runtime.storage.database import RuntimeDatabase

SETTING_KEY = "mac.google_account"
GOOGLE_HOSTS = frozenset({"calendar.google.com", "mail.google.com", "drive.google.com", "docs.google.com",
                          "meet.google.com"})
# A bare host opens the app's home: keep it explicit so the account parameter survives Google's redirect.
_HOME_PATHS = {"calendar.google.com": "/calendar/r", "mail.google.com": "/mail/", "drive.google.com": "/drive/"}
_EMAIL = re.compile(r"^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$")
# "/u/0" in a Google path picks the browser's first signed-in account; authuser picks the right one instead.
_ACCOUNT_INDEX = re.compile(r"/u/\d+(?=/|$)")


def normalize_email(value: object) -> str | None:
    """The address in lower case, or None when it is not an email address."""
    if not isinstance(value, str):
        return None
    text = value.strip().strip("<>").strip()
    if text.lower().startswith("mailto:"):
        text = text[7:]
    return text.lower() if len(text) <= 254 and _EMAIL.match(text) else None


def with_google_account(url: str, email: str | None) -> str:
    """``url`` with ``authuser=<email>`` when it is a Google Calendar, Gmail, Drive, Docs or Meet link that does not
    name an account yet; any other link (or no email) comes back unchanged."""
    account = normalize_email(email)
    if account is None:
        return url
    try:
        parts = urlsplit(url.strip())
    except ValueError:
        return url
    host = (parts.hostname or "").lower()
    if parts.scheme.lower() not in ("http", "https") or host not in GOOGLE_HOSTS:
        return url
    query = parse_qsl(parts.query, keep_blank_values=True)
    if any(key.lower() == "authuser" for key, _ in query):
        return url
    path = _ACCOUNT_INDEX.sub("", parts.path) or ""
    if path in ("", "/") and host in _HOME_PATHS:
        path = _HOME_PATHS[host]
    pinned = "authuser=" + quote(account, safe="@")
    return urlunsplit((parts.scheme, parts.netloc, path, f"{parts.query}&{pinned}" if parts.query else pinned,
                       parts.fragment))


class GoogleAccountSetting:
    """Reads and writes ``mac.google_account``; ``resolve`` falls back to the calendar connection."""

    def __init__(self, database: RuntimeDatabase) -> None:
        self._database = database

    def get(self) -> str | None:
        try:
            with self._database.connect() as connection:
                row = connection.execute("SELECT setting_value FROM provider_settings WHERE setting_key = ?",
                                         (SETTING_KEY,)).fetchone()
        except sqlite3.Error:
            return None
        return normalize_email(row[0]) if row and row[0] is not None else None

    def set(self, value: object) -> str | None:
        """Save an address (or clear it with None / an empty string). Raises ValueError for anything else."""
        if value is None or (isinstance(value, str) and not value.strip()):
            with self._database.connect() as connection:
                connection.execute("DELETE FROM provider_settings WHERE setting_key = ?", (SETTING_KEY,))
                connection.commit()
            return None
        email = normalize_email(value)
        if email is None:
            raise ValueError("googleAccount must be an email address, e.g. name@example.com.")
        now = datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
        with self._database.connect() as connection:
            connection.execute(
                "INSERT INTO provider_settings(setting_key, setting_value, updated_at) VALUES (?, ?, ?) "
                "ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value, "
                "updated_at = excluded.updated_at",
                (SETTING_KEY, email, now),
            )
            connection.commit()
        return email

    def from_calendar(self) -> str | None:
        """The first calendar account (secret iCal feeds first) whose label or calendar name is an email."""
        try:
            with self._database.connect() as connection:
                accounts = connection.execute(
                    "SELECT account_id, label FROM calendar_accounts "
                    "ORDER BY CASE provider_type WHEN 'ics_subscription' THEN 0 ELSE 1 END, created_at, account_id"
                ).fetchall()
                for account_id, label in accounts:
                    email = normalize_email(label)
                    if email:
                        return email
                    names = connection.execute(
                        "SELECT DISTINCT calendar_name FROM calendar_events WHERE account_id = ? "
                        "AND calendar_name LIKE '%@%' LIMIT 5", (account_id,)
                    ).fetchall()
                    for (name,) in names:
                        email = normalize_email(name)
                        if email:
                            return email
        except sqlite3.Error:
            return None
        return None

    def resolve(self) -> tuple[str | None, str | None]:
        """(email, source): source is "setting", "calendar", or None when there is no account."""
        saved = self.get()
        if saved:
            return saved, "setting"
        derived = self.from_calendar()
        return (derived, "calendar") if derived else (None, None)

    def email(self) -> str | None:
        return self.resolve()[0]

    def view(self) -> dict[str, object]:
        saved, derived = self.get(), self.from_calendar()
        return {"email": saved or derived, "source": "setting" if saved else "calendar" if derived else None,
                "fromCalendar": derived}

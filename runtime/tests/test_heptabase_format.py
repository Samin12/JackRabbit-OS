from __future__ import annotations

from datetime import UTC, datetime
import unittest
from unittest import mock
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from heptabase_fakes import JournalHarness

from sam_runtime.domains.heptabase_journal import format as fmt
from sam_runtime.domains.heptabase_journal import localtime
from sam_runtime.domains.heptabase_journal.localtime import (US_EASTERN_FALLBACK, UnknownTimezone, clock_label,
                                                             journal_date, resolve_zone, zone_source)

NY = ZoneInfo("America/New_York")


def _utc(*parts: int) -> float:
    return datetime(*parts, tzinfo=UTC).timestamp()


class EscapingTest(unittest.TestCase):
    def test_inline_syntax_is_escaped_and_words_survive(self) -> None:
        text = "the *orb* needs <glow> & 50% less_blur, costs $5 [x](y) {{card}} `code` ~strike~ a|b &amp;"
        escaped = fmt.escape_verbatim(text)
        self.assertEqual(
            "the \\*orb\\* needs \\<glow\\> & 50% less\\_blur, costs \\$5 \\[x\\](y) {\\{card}} "
            "\\`code\\` \\~strike\\~ a\\|b \\&amp;",
            escaped,
        )
        self.assertEqual(fmt.match_key(text), fmt.match_key(escaped))
        self.assertEqual(text, fmt.unescape_markdown(escaped).replace("{\\{", "{{"))

    def test_line_start_block_syntax(self) -> None:
        self.assertEqual("1\\. first", fmt.escape_verbatim("1. first", line_start=True))
        self.assertEqual("2\\) second", fmt.escape_verbatim("2) second", line_start=True))
        for char in "#>+-=":
            self.assertEqual("\\" + char + " x", fmt.escape_verbatim(char + " x", line_start=True))
        self.assertEqual("# x", fmt.escape_verbatim("# x"), "mid-line # needs no escape")
        self.assertEqual("one two", fmt.escape_verbatim("  one \n\n two  "), "whitespace only normalized")

    def test_note_and_session_rendering(self) -> None:
        note = fmt.render_note("13:42", "I think the orb should pulse slower when it's listening.")
        self.assertEqual("**13:42** I think the orb should pulse slower when it's listening.", note.content)
        self.assertEqual("13:42 I think the orb should pulse slower when it's listening.", note.plain_content)
        blocks = fmt.render_session(fmt.session_header("13:40", "13:46"), [
            fmt.SessionLine(1, "13:40", "user", "What's on my calendar today?"),
            fmt.SessionLine(2, "13:44", "user", "Start a T3 thread called orb polish"),
            fmt.SessionLine(3, "13:44", "activity", 'T3: started thread "orb polish"'),
            fmt.SessionLine(4, None, "user", "1. not a list"),
        ])
        self.assertEqual(1, len(blocks))
        self.assertEqual(
            "**R1 voice · 13:40–13:46**\n\n"
            "- 13:40 What's on my calendar today?\n\n"
            "- 13:44 Start a T3 thread called orb polish\n\n"
            '- 13:44 <hepta-color type="text" color="gray">↳ T3: started thread "orb polish"</hepta-color>\n\n'
            "- 1\\. not a list",
            blocks[0].content,
        )
        self.assertNotIn("<hepta", blocks[0].plain_content)
        self.assertNotIn("**", blocks[0].plain_content)
        self.assertEqual(fmt.fingerprint(blocks[0].content), fmt.fingerprint(blocks[0].plain_content))
        self.assertEqual("R1 voice · 13:40", fmt.session_header("13:40", "13:40"))

    def test_long_session_splits_under_call_budget(self) -> None:
        lines = [fmt.SessionLine(i, "10:00", "user", "x" * 900) for i in range(40)]
        blocks = fmt.render_session("R1 voice · 10:00–11:00", lines)
        self.assertGreater(len(blocks), 1)
        self.assertTrue(blocks[0].content.startswith("**R1 voice · 10:00–11:00 (1/"))
        self.assertTrue(all(block.size <= fmt.MAX_CALL_BYTES for block in blocks))

    def test_secret_scrub(self) -> None:
        cases = {
            "my stripe key is sk_live_51Habcdefghijklmn ok": "my stripe key is [redacted] ok",
            "openai sk-proj-abcdefghijklmnopqrstuv1234": "openai [redacted]",
            "token ghp_abcdefghijklmnopqrstuvwxyz0123": "token [redacted]",
            "aws AKIAABCDEFGHIJKLMNOP done": "aws [redacted] done",
            "jwt eyJhbGciOiJIUzI1.eyJzdWIiOiIxMjM0.SflKxwRJSMeKKF2QT4": "jwt [redacted]",
            "the code is 482 913, thanks": "the code is [redacted], thanks",
            "my pin is 4821": "my pin is [redacted]",
            "password is hunter2, remember": "password is [redacted], remember",
            "Bearer abcdefghijklmnopqrstuvwx": "[redacted]",
        }
        for raw, expected in cases.items():
            with self.subTest(raw=raw):
                self.assertEqual(expected, fmt.scrub_secrets(raw))
        for harmless in ("I paid $5 for 3 coffees", "call me at 5", "the skeleton key is in the drawer",
                         "Room 4821 is booked"):
            self.assertEqual(harmless, fmt.scrub_secrets(harmless))

    def test_fingerprint_finds_entry_in_read_journal_output(self) -> None:
        note = fmt.render_note("09:05", "ship the less_blur build & test it")
        raw = ("journal [2026-10-07] 3 lines\n1\tWhat rabbit would be useful for\n2\t\n"
               "3\t**09:05** ship the less\\\\_blur build & test it")
        self.assertTrue(fmt.journal_contains(raw, fmt.fingerprint(note.content)))
        self.assertFalse(fmt.journal_contains(raw, fmt.fingerprint(fmt.render_note("09:06", "other").content)))
        self.assertFalse(fmt.journal_contains("", ""))
        self.assertEqual("What rabbit would be useful for\n\n**09:05** ship the less\\\\_blur build & test it",
                         fmt.journal_plain_text(raw))


class TimezoneTest(unittest.TestCase):
    def test_late_evening_new_york_stays_on_the_local_date(self) -> None:
        late = datetime(2026, 10, 7, 23, 30, tzinfo=NY).timestamp()
        self.assertEqual("2026-10-08", datetime.fromtimestamp(late, UTC).date().isoformat(), "device clock is GMT")
        self.assertEqual("2026-10-07", journal_date(late, resolve_zone("America/New_York")))
        self.assertEqual("23:30", clock_label(late, resolve_zone("America/New_York")))
        after_midnight = datetime(2026, 10, 8, 0, 30, tzinfo=NY).timestamp()
        self.assertEqual("2026-10-08", journal_date(after_midnight, resolve_zone("America/New_York")))

    def test_dst_boundaries(self) -> None:
        zone = resolve_zone("America/New_York")
        self.assertEqual("01:59", clock_label(_utc(2026, 3, 8, 6, 59), zone))
        self.assertEqual("03:00", clock_label(_utc(2026, 3, 8, 7, 0), zone))
        self.assertEqual("01:30", clock_label(_utc(2026, 11, 1, 5, 30), zone))
        self.assertEqual("01:30", clock_label(_utc(2026, 11, 1, 6, 30), zone), "repeated hour")
        self.assertEqual("2026-11-01", journal_date(_utc(2026, 11, 2, 4, 59), zone))
        self.assertEqual("2026-11-02", journal_date(_utc(2026, 11, 2, 5, 0), zone))

    def test_fallback_rule_when_tz_data_is_missing(self) -> None:
        def missing(name: str):
            raise ZoneInfoNotFoundError(name)

        with mock.patch.object(localtime, "zoneinfo_factory", missing):
            zone = resolve_zone("America/New_York")
            self.assertIs(US_EASTERN_FALLBACK, zone)
            self.assertEqual("fallback", zone_source("America/New_York"))
            late = datetime(2026, 10, 7, 23, 30, tzinfo=NY).timestamp()
            self.assertEqual("2026-10-07", journal_date(late, zone))
            self.assertEqual("23:30", clock_label(late, zone))
            winter = datetime(2026, 1, 15, 23, 30, tzinfo=NY).timestamp()
            self.assertEqual(("2026-01-15", "23:30"), (journal_date(winter, zone), clock_label(winter, zone)))
            with self.assertRaises(UnknownTimezone):
                resolve_zone("Europe/London")
            self.assertEqual("unknown", zone_source("Europe/London"))
            self.assertIs(localtime.timezone.utc, resolve_zone("UTC"))
        self.assertEqual("tzdata", zone_source("America/New_York"))

    def test_fallback_matches_zoneinfo_hour_by_hour(self) -> None:
        start = _utc(2024, 1, 1, 0, 0)
        for hour in range(0, 3 * 366 * 24, 1):
            moment = start + hour * 3600 + 1800
            real = datetime.fromtimestamp(moment, NY)
            rule = datetime.fromtimestamp(moment, US_EASTERN_FALLBACK)
            self.assertEqual((real.replace(tzinfo=None), real.utcoffset()), (rule.replace(tzinfo=None), rule.utcoffset()))


class SettingsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.h = JournalHarness()
        self.addCleanup(self.h.close)

    def test_defaults_and_strict_updates(self) -> None:
        settings = self.h.service.settings()
        self.assertEqual({"autoSessions": False, "includeActions": True, "includeAssistant": False,
                          "redactSecrets": True, "timezone": "America/New_York"}, settings.view())
        view = self.h.service.save_settings({"includeAssistant": True, "timezone": "America/Chicago"})
        self.assertEqual("America/Chicago", view["settings"]["timezone"])
        self.assertTrue(view["settings"]["includeAssistant"])
        for bad in ({"autoSessions": "yes"}, {"timezone": "Mars/Olympus"}, {"color": "blue"}, {"timezone": ""}):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                self.h.service.save_settings(bad)


if __name__ == "__main__":
    unittest.main()

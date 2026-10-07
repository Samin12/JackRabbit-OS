from __future__ import annotations

import json
import unittest
from urllib.parse import parse_qs, urlsplit

from heptabase_fakes import JournalHarness

from sam_runtime.domains.heptabase_journal import (AuthorizationError, HEPTABASE_CONNECTION_ID,
                                                   LOOPBACK_REDIRECT_URI, NotConnected, ReconnectRequired)
from sam_runtime.domains.heptabase_journal.client import HttpTransport


class HeptabaseOAuthTest(unittest.TestCase):
    def setUp(self) -> None:
        self.h = JournalHarness()
        self.addCleanup(self.h.close)

    def test_dcr_is_cached_per_redirect_uri_and_url_carries_pkce_state_resource_consent(self) -> None:
        first = self.h.service.connect_start("loopback", None)
        second = self.h.service.connect_start("loopback", None)
        device = self.h.service.connect_start("device", "https://192.168.1.186:8443")
        self.assertEqual(LOOPBACK_REDIRECT_URI, first["redirectUri"])
        self.assertEqual("https://192.168.1.186:8443/v1/heptabase/oauth/callback", device["redirectUri"])
        self.assertEqual(2, len(self.h.fake.registrations), "one DCR per distinct redirect URI")
        self.assertEqual("none", self.h.fake.registrations[0]["token_endpoint_auth_method"])
        query = {k: v[0] for k, v in parse_qs(urlsplit(str(first["authorizationUrl"])).query).items()}
        self.assertEqual("S256", query["code_challenge_method"])
        self.assertEqual(43, len(query["code_challenge"]))
        self.assertEqual("consent", query["prompt"])
        self.assertEqual(self.h.fake.base + "/mcp", query["resource"])
        self.assertEqual("space:read space:write offline_access", query["scope"])
        self.assertGreaterEqual(len(query["state"]), 43)  # 256-bit
        second_state = parse_qs(urlsplit(str(second["authorizationUrl"])).query)["state"][0]
        self.assertNotEqual(query["state"], second_state)

    def test_reregister_forces_a_fresh_client(self) -> None:
        self.h.service.connect_start("loopback", None)
        self.h.service.connect_start("loopback", None, reregister=True)
        self.assertEqual(2, len(self.h.fake.registrations))

    def test_device_redirect_requires_a_forwarded_https_origin(self) -> None:
        with self.assertRaises(AuthorizationError) as caught:
            self.h.service.connect_start("device", None)
        self.assertEqual("device_redirect_unavailable", caught.exception.code)
        with self.assertRaises(AuthorizationError):
            self.h.service.connect_start("device", "http://192.168.1.186:8443")
        with self.assertRaises(AuthorizationError):
            self.h.service.connect_start("elsewhere", None)

    def test_exchange_seals_tokens_and_marks_connected(self) -> None:
        view = self.h.connect()
        self.assertTrue(view["connected"])
        self.assertTrue(view["writeVerified"])
        self.assertTrue(view["refreshAvailable"])
        exchange = self.h.fake.token_requests[-1]
        self.assertEqual("authorization_code", exchange["grant_type"])
        self.assertEqual(LOOPBACK_REDIRECT_URI, exchange["redirect_uri"])
        self.assertIn("code_verifier", exchange)
        self.assertEqual([f"connection:{HEPTABASE_CONNECTION_ID}:credential"], sorted(set(self.h.bridge.sealed)))
        access = next(iter(self.h.fake.access))
        refresh = self.h.fake.grants[0].refresh_token
        with self.h.database.connect() as connection:
            dump = "\n".join(connection.iterdump())
        self.assertNotIn(access, dump, "plaintext tokens must never reach SQLite")
        self.assertNotIn(refresh, dump)
        self.assertIsNotNone(self.h.credentials.get_envelope(HEPTABASE_CONNECTION_ID))

    def test_state_is_single_use_and_expires(self) -> None:
        started = self.h.service.connect_start("loopback", None)
        params = self.h.fake.authorize(str(started["authorizationUrl"]))
        with self.assertRaises(AuthorizationError) as wrong:
            self.h.service.complete_authorization(state="forged", code=params["code"], issuer=params["iss"])
        self.assertEqual("invalid_state", wrong.exception.code)
        self.h.service.complete_authorization(state=params["state"], code=params["code"], issuer=params["iss"])
        with self.assertRaises(AuthorizationError) as reused:
            self.h.service.complete_authorization(state=params["state"], code=params["code"], issuer=params["iss"])
        self.assertEqual("invalid_state", reused.exception.code)

        later = self.h.service.connect_start("loopback", None)
        late_params = self.h.fake.authorize(str(later["authorizationUrl"]))
        self.h.clock.advance(601)
        with self.assertRaises(AuthorizationError) as expired:
            self.h.service.complete_authorization(state=late_params["state"], code=late_params["code"],
                                                  issuer=late_params["iss"])
        self.assertEqual("state_expired", expired.exception.code)

    def test_issuer_is_required_and_checked(self) -> None:
        for issuer in (None, "https://evil.example"):
            started = self.h.service.connect_start("loopback", None)
            params = self.h.fake.authorize(str(started["authorizationUrl"]))
            with self.assertRaises(AuthorizationError) as caught:
                self.h.service.complete_authorization(state=params["state"], code=params["code"], issuer=issuer)
            self.assertEqual("issuer_mismatch", caught.exception.code)
        self.assertFalse(self.h.service.connected())
        self.assertEqual(0, len([r for r in self.h.fake.token_requests if r["grant_type"] == "authorization_code"]),
                         "no code exchange when iss fails")

    def test_denied_authorization_consumes_state(self) -> None:
        started = self.h.service.connect_start("loopback", None)
        params = self.h.fake.authorize(str(started["authorizationUrl"]), deny=True)
        with self.assertRaises(AuthorizationError) as caught:
            self.h.service.complete_authorization(state=params["state"], code=None, issuer=params["iss"],
                                                  error=params["error"])
        self.assertEqual("authorization_denied", caught.exception.code)
        with self.assertRaises(AuthorizationError):
            self.h.service.complete_authorization(state=params["state"], code="x", issuer=params["iss"])

    def test_missing_write_tool_rejects_and_revokes(self) -> None:
        self.h.fake.tools = ["read_journal_range"]
        with self.assertRaises(AuthorizationError) as caught:
            self.h.connect()
        self.assertEqual("write_scope_missing", caught.exception.code)
        self.assertFalse(self.h.service.connected())
        self.assertIsNone(self.h.credentials.get_envelope(HEPTABASE_CONNECTION_ID))
        self.assertTrue(self.h.fake.revoked)

    def test_refresh_rotates_and_persists_new_refresh_token_before_use(self) -> None:
        self.h.connect()
        oauth = self.h.service._oauth  # noqa: SLF001
        grant = self.h.fake.grants[0]
        first_refresh = grant.refresh_token
        self.h.clock.advance(172_800 - 200)  # inside the 300 s leeway
        token = oauth.access_token()
        self.assertNotEqual(first_refresh, grant.refresh_token, "server rotated")
        stored = json.loads(self.h.bridge.openConnectionCredential(
            f"connection:{HEPTABASE_CONNECTION_ID}:credential", self.h.credentials.get_envelope(HEPTABASE_CONNECTION_ID)))
        self.assertEqual(grant.refresh_token, stored["refresh_token"], "rotated token sealed")
        self.assertEqual(token, stored["access_token"])
        # A second rotation uses the new token (reusing the old one would kill the grant).
        self.h.clock.advance(172_800)
        oauth.access_token()
        self.assertFalse(grant.revoked)
        self.assertEqual(3, len([r for r in self.h.fake.token_requests if r["grant_type"] in ("authorization_code", "refresh_token")]))
        self.assertEqual(self.h.fake.base + "/mcp", self.h.fake.token_requests[-1]["resource"])

    def test_concurrent_callers_refresh_once(self) -> None:
        import threading

        self.h.connect()
        oauth = self.h.service._oauth  # noqa: SLF001
        self.h.clock.advance(172_800)
        results: list[str] = []
        threads = [threading.Thread(target=lambda: results.append(oauth.access_token())) for _ in range(6)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        self.assertEqual(1, len(set(results)))
        self.assertEqual(1, len([r for r in self.h.fake.token_requests if r["grant_type"] == "refresh_token"]))
        self.assertFalse(self.h.fake.grants[0].revoked)

    def test_401_refreshes_once_then_retries(self) -> None:
        self.h.connect()
        self.h.fake.expire_all_access_tokens()
        text = self.h.service.read_journal("2026-10-07")
        self.assertEqual("2026-10-07", text["date"])
        self.assertEqual(1, len([r for r in self.h.fake.token_requests if r["grant_type"] == "refresh_token"]))

    def test_invalid_grant_requires_reconnect_and_pauses_queue(self) -> None:
        self.h.connect()
        self.h.fake.refresh_failures.append("invalid_grant")
        self.h.clock.advance(172_800)
        with self.assertRaises(ReconnectRequired):
            self.h.service._oauth.access_token()  # noqa: SLF001
        view = self.h.service.management_view()
        self.assertEqual("reconnect_required", view["state"])
        self.assertTrue(view["needsReconnect"])
        self.assertTrue(self.h.service.device_status()["needsReconnect"])
        result = self.h.service.record_note("buy oat milk")
        self.assertEqual("queued", result["state"])
        self.h.service.drain()
        self.assertEqual([], self.h.fake.appends(), "nothing is sent while reconnect is required")
        self.assertTrue(self.h.service.management_view()["queue"]["paused"])
        # Reconnecting resumes delivery of the kept entries.
        self.h.connect()
        self.h.service.drain()
        self.assertEqual(1, len(self.h.fake.appends()))
        self.assertIn("buy oat milk", self.h.fake.appends()[0][1])

    def test_refresh_refusals_never_fail_queued_entries(self) -> None:
        self.h.connect()
        self.h.clock.advance(172_800 + 10)
        self.h.fake.expire_all_access_tokens()
        # A transient token-endpoint failure keeps the entries queued for a retry.
        self.h.fake.refresh_failures.append("503")
        self.h.service.record_note("first words for the journal", wait=False)
        self.h.service.drain()
        self.assertEqual(["pending"], [row["state"] for row in self.h.rows()])
        self.assertEqual("connected", self.h.service.management_view()["state"])
        # Any other refusal of the grant (here 400 server_error) needs a reconnect, not a failure.
        self.h.clock.advance(60)
        self.h.fake.refresh_failures.append("400")
        self.h.service.record_note("second words for the journal", wait=False)
        self.h.service.drain()
        self.assertEqual(["pending", "pending"], [row["state"] for row in self.h.rows()])
        view = self.h.service.management_view()
        self.assertEqual("reconnect_required", view["state"])
        self.assertEqual(0, view["queue"]["failed"])
        self.h.connect()
        self.h.service.drain()
        self.assertEqual(["sent", "sent"], [row["state"] for row in self.h.rows()])

    def test_network_failure_during_refresh_keeps_a_still_valid_token(self) -> None:
        self.h.connect()
        oauth = self.h.service._oauth  # noqa: SLF001
        before = oauth.access_token()
        self.h.clock.advance(172_800 - 200)
        self.h.fake.refresh_failures.append("503")
        self.assertEqual(before, oauth.access_token(), "still valid for 200 s: use it, refresh later")
        self.assertEqual("connected", self.h.service.management_view()["state"])

    def test_disconnect_revokes_and_forgets(self) -> None:
        self.h.connect()
        refresh = self.h.fake.grants[0].refresh_token
        view = self.h.service.disconnect()
        self.assertFalse(view["connected"])
        self.assertIn(refresh, self.h.fake.revoked)
        self.assertIsNone(self.h.credentials.get_envelope(HEPTABASE_CONNECTION_ID))
        with self.assertRaises(NotConnected):
            self.h.service.record_note("hello")

    def test_disconnect_fits_inside_the_proxy_timeout(self) -> None:
        timeouts: list[float | None] = []

        class Recording(HttpTransport):
            def request(self, method, url, **kwargs):  # noqa: ANN001
                if url.endswith("/token/revocation"):
                    timeouts.append(kwargs.get("timeout"))
                return super().request(method, url, **kwargs)

        harness = JournalHarness(transport=Recording(timeout=20.0))
        self.addCleanup(harness.close)
        harness.connect()
        harness.service.disconnect()
        self.assertEqual(2, len(timeouts), "refresh and access token are both revoked")
        # ManagementRuntimeProxy gives /v1/management/heptabase/disconnect 30 s in total.
        self.assertLess(sum(value or 20.0 for value in timeouts), 30.0)

    def test_reconnect_revokes_the_previous_grant(self) -> None:
        self.h.connect()
        old_refresh = self.h.fake.grants[0].refresh_token
        self.h.connect()
        self.assertIn(old_refresh, self.h.fake.revoked)
        self.assertEqual(1, len(self.h.fake.registrations), "loopback client id reused")

    def test_reconnect_keeps_a_reused_grant_alive(self) -> None:
        self.h.fake.oidc_grants = True
        self.h.connect()
        self.h.connect()  # same client and browser session: the issuer reuses the live grant
        self.assertEqual(1, len(self.h.fake.grants))
        self.assertEqual(0, len(self.h.fake.revoked), "revoking the old token would revoke the new connection")
        self.assertEqual("sent", self.h.service.record_note("still connected after reconnecting")["state"])

    def test_reconnect_with_a_new_grant_revokes_the_old_one(self) -> None:
        self.h.fake.oidc_grants = True
        self.h.connect()
        old = self.h.fake.grants[0]
        old.revoked = True  # the grant ended on Heptabase's side, so the next Allow creates a new one
        self.h.connect()
        self.assertEqual(2, len(self.h.fake.grants))
        self.assertIn(old.refresh_token, self.h.fake.revoked)
        self.assertEqual("sent", self.h.service.record_note("connected with the new grant")["state"])

    def test_no_refresh_token_is_surfaced(self) -> None:
        self.h.fake.issue_refresh = False
        view = self.h.connect()
        self.assertFalse(view["refreshAvailable"])
        self.assertIn("48 hours", str(view["lastError"]))
        self.h.clock.advance(172_800)
        with self.assertRaises(ReconnectRequired):
            self.h.service._oauth.access_token()  # noqa: SLF001

    def test_import_dev_tokens(self) -> None:
        # Mint a real fake grant, then import it as the Mac-side A2 bootstrap would.
        started = self.h.service.connect_start("loopback", None)
        params = self.h.fake.authorize(str(started["authorizationUrl"]))
        tokens = self.h.service._oauth.complete(state=params["state"], code=params["code"], issuer=params["iss"])  # noqa: SLF001
        self.h.service._oauth.forget()  # noqa: SLF001
        view = self.h.service.import_tokens({
            "clientId": tokens["client_id"], "accessToken": tokens["access_token"],
            "refreshToken": tokens["refresh_token"], "expiresAt": int(tokens["expires_at"]) * 1000,
            "scope": tokens["scope"],
        })
        self.assertTrue(view["connected"])
        self.assertEqual(int(tokens["expires_at"]), self.h.service._settings.state().access_expires_at)  # noqa: SLF001
        with self.assertRaises(AuthorizationError):
            self.h.service.import_tokens({"clientId": "c", "accessToken": "bogus", "expiresAt": 1})


if __name__ == "__main__":
    unittest.main()

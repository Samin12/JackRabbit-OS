from __future__ import annotations

import socket
import unittest

from sam_runtime.domains.t3.client import (
    T3EndpointError,
    T3HttpClient,
    T3RequestError,
    T3Unauthorized,
    T3Unavailable,
    normalize_pairing_code,
    parse_server_url,
)

from t3_fixtures import PAIRING_CODE, FakeT3Server, thread


class T3ClientPairingTest(unittest.TestCase):
    def test_pairing_exchange_sends_exact_token_exchange_form(self) -> None:
        with FakeT3Server() as fake:
            result = T3HttpClient(fake.endpoint).exchange_pairing_code(PAIRING_CODE)
            self.assertEqual("Bearer", result["token_type"])
            self.assertEqual(
                {
                    "grant_type": ["urn:ietf:params:oauth:grant-type:token-exchange"],
                    "subject_token": [PAIRING_CODE],
                    "subject_token_type": ["urn:t3:params:oauth:token-type:environment-bootstrap"],
                    "requested_token_type": ["urn:ietf:params:oauth:token-type:access_token"],
                    "scope": ["orchestration:read orchestration:operate"],
                    "client_label": ["Rabbit R1"],
                    "client_device_type": ["mobile"],
                    "client_os": ["android"],
                },
                fake.forms[0],
            )
            token_request = fake.requests[-1]
            self.assertEqual("application/x-www-form-urlencoded", token_request["headers"]["Content-Type"])
            self.assertNotIn("Authorization", token_request["headers"])

    def test_rejected_or_reused_code_raises_unauthorized(self) -> None:
        with FakeT3Server() as fake:
            client = T3HttpClient(fake.endpoint)
            client.exchange_pairing_code(PAIRING_CODE)
            with self.assertRaises(T3Unauthorized) as caught:
                client.exchange_pairing_code(PAIRING_CODE)
            self.assertEqual("invalid_credential", caught.exception.reason)

    def test_shell_uses_bearer_and_gzip(self) -> None:
        with FakeT3Server() as fake:
            token = T3HttpClient(fake.endpoint).exchange_pairing_code(PAIRING_CODE)["access_token"]
            fake.set_threads([thread("t-1", "Fix login redirect")])
            shell = T3HttpClient(fake.endpoint, token).shell()
            self.assertEqual("Fix login redirect", shell["threads"][0]["title"])
            headers = fake.requests[-1]["headers"]
            self.assertEqual(f"Bearer {token}", headers["Authorization"])
            self.assertIn("gzip", headers["Accept-Encoding"])

    def test_revoked_token_raises_unauthorized_and_missing_thread_is_404(self) -> None:
        with FakeT3Server() as fake:
            token = T3HttpClient(fake.endpoint).exchange_pairing_code(PAIRING_CODE)["access_token"]
            client = T3HttpClient(fake.endpoint, token)
            with self.assertRaises(T3RequestError) as missing:
                client.thread("does-not-exist", turn_limit=1)
            self.assertEqual(404, missing.exception.status)
            fake.revoke_all()
            with self.assertRaises(T3Unauthorized):
                client.shell()

    def test_unreachable_server_raises_unavailable_without_token_in_message(self) -> None:
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
        listener.close()
        client = T3HttpClient(parse_server_url(f"http://127.0.0.1:{port}"), "secret-token-value", timeout=1.0)
        with self.assertRaises(T3Unavailable) as caught:
            client.shell()
        self.assertNotIn("secret-token-value", str(caught.exception))


class T3EndpointValidationTest(unittest.TestCase):
    def test_http_only_for_private_hosts(self) -> None:
        self.assertEqual("http://192.168.1.183:3773", parse_server_url("http://192.168.1.183:3773").url)
        self.assertEqual("http://192.168.1.183:3773", parse_server_url("192.168.1.183").url)
        self.assertEqual("http://10.0.0.2:3773", parse_server_url(" http://10.0.0.2:3773/ ").url)
        self.assertEqual("http://100.101.102.103:3773", parse_server_url("http://100.101.102.103:3773").url)
        self.assertEqual("http://127.0.0.1:3773", parse_server_url("http://127.0.0.1:3773").url)
        for public in ("http://8.8.8.8:3773", "http://example.com:3773"):
            with self.subTest(public=public), self.assertRaises(T3EndpointError):
                parse_server_url(public, resolver=lambda host: ("93.184.216.34",))

    def test_https_any_host_and_resolved_private_names(self) -> None:
        self.assertEqual("https://mac.tailnet.ts.net", parse_server_url("https://mac.tailnet.ts.net").url)
        self.assertEqual(
            "http://studio.local:3773",
            parse_server_url("http://studio.local:3773", resolver=lambda host: ("192.168.1.183",)).url,
        )

    def test_rejects_paths_credentials_and_bad_schemes(self) -> None:
        for bad in ("ftp://192.168.1.2", "http://user:pw@192.168.1.2", "http://192.168.1.2/api", "http://192.168.1.2/?x=1", ""):
            with self.subTest(bad=bad), self.assertRaises(T3EndpointError):
                parse_server_url(bad)

    def test_pairing_code_normalization(self) -> None:
        self.assertEqual((PAIRING_CODE, None), normalize_pairing_code("abcd-2345-efgh"))
        self.assertEqual(
            (PAIRING_CODE, "http://192.168.1.183:3773"),
            normalize_pairing_code(f"http://192.168.1.183:3773/pair#token={PAIRING_CODE}"),
        )
        for bad in ("short", "ABCD2345EFG1", "ABCD2345EFGHI"):
            with self.subTest(bad=bad), self.assertRaises(T3EndpointError):
                normalize_pairing_code(bad)


if __name__ == "__main__":
    unittest.main()

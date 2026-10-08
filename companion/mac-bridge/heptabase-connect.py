#!/usr/bin/env python3
"""Connect the R1's Heptabase journal from this Mac (Option A: loopback bounce).

The R1 owns the OAuth grant: it registers the client, creates the PKCE secret and
the single-use state, and exchanges the code itself. This helper only:

  1. pairs with (or reuses) an R1 management session,
  2. asks the R1 to start an authorization  (POST /v1/management/heptabase/connect/start),
  3. listens on the loopback redirect URI the R1 returned (http://127.0.0.1:53682/callback),
  4. opens Heptabase's Allow screen in Chrome, and
  5. forwards {code, state, iss} from the redirect to the R1 (POST /v1/heptabase/oauth/callback).

Nothing secret is stored on the Mac, and codes/tokens are never printed.

TLS: the R1 serves a self-signed LAN certificate. By default the helper pins it on
first use (trust-on-first-use) and refuses a different certificate afterwards
(--repin to accept a new one after a device reset). --insecure skips verification:
acceptable on a trusted LAN because the forwarded code is single-use, bound to the
R1's PKCE verifier, and useless without it, but pinning is preferred.

Usage:
  heptabase-connect.py --r1 https://192.168.1.20:8443 --pairing-code 123456
  heptabase-connect.py --r1 https://192.168.1.20:8443 --cookie 'sam_session=…' --csrf '…'
Stdlib only. Exit status 0 = connected.
"""

from __future__ import annotations

import argparse
import hashlib
import html
import http.client
import http.server
import json
import os
from pathlib import Path
import shlex
import socket
import ssl
import subprocess
import sys
import threading
from urllib.parse import parse_qs, urlsplit

DEFAULT_PIN_FILE = Path.home() / ".config" / "samrabbit" / "r1-certificate-pins.json"
DEFAULT_BROWSER = 'open -a "Google Chrome"'


class HelperError(RuntimeError):
    pass


# --------------------------------------------------------------------------- TLS + HTTP to the R1

class R1Client:
    def __init__(self, origin: str, *, pin_file: Path | None, insecure: bool, repin: bool, timeout: float = 60.0) -> None:
        parsed = urlsplit(origin)
        if parsed.scheme != "https" or not parsed.hostname or parsed.path not in ("", "/"):
            raise HelperError("--r1 must look like https://192.168.1.20:8443")
        self.host = parsed.hostname
        self.port = parsed.port or 8443
        self.origin = f"https://{self.host}:{self.port}"
        self.pin_file = pin_file
        self.insecure = insecure
        self.repin = repin
        self.timeout = timeout
        self.cookie = ""
        self.csrf = ""

    def _context(self) -> ssl.SSLContext:
        context = ssl.create_default_context()
        # Verification is replaced by certificate pinning (or skipped with --insecure); the
        # R1's certificate is self-signed for its LAN address.
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        return context

    def _check_pin(self, der: bytes) -> None:
        if self.insecure:
            return
        fingerprint = hashlib.sha256(der).hexdigest()
        key = f"{self.host}:{self.port}"
        pins: dict[str, str] = {}
        if self.pin_file and self.pin_file.exists():
            try:
                pins = json.loads(self.pin_file.read_text())
            except ValueError:
                pins = {}
        known = pins.get(key)
        if known and known != fingerprint and not self.repin:
            raise HelperError(
                f"The R1 at {key} presented a different TLS certificate than last time "
                f"(pinned {known[:12]}…, got {fingerprint[:12]}…). If the R1 was reset, rerun with --repin."
            )
        if known != fingerprint and self.pin_file:
            pins[key] = fingerprint
            self.pin_file.parent.mkdir(parents=True, exist_ok=True)
            self.pin_file.write_text(json.dumps(pins, indent=2))
            os.chmod(self.pin_file, 0o600)
            print(f"Pinned the R1 certificate {fingerprint[:12]}… for {key}.")

    def request(self, method: str, path: str, body: dict[str, object] | None = None, *,
                session: bool = False) -> tuple[int, dict[str, str], dict[str, object]]:
        connection = http.client.HTTPSConnection(self.host, self.port, timeout=self.timeout, context=self._context())
        try:
            connection.connect()
            self._check_pin(connection.sock.getpeercert(binary_form=True))  # before any byte is sent
            headers = {"Accept": "application/json", "Host": f"{self.host}:{self.port}"}
            payload = None
            if body is not None:
                payload = json.dumps(body).encode()
                headers["Content-Type"] = "application/json"
            if session:
                headers.update({"Origin": self.origin, "Cookie": self.cookie, "X-CSRF-Token": self.csrf})
            elif path == "/v1/management/pair":
                headers["Origin"] = self.origin
            connection.request(method, path, body=payload, headers=headers)
            response = connection.getresponse()
            raw = response.read(65536)
            response_headers = {key.lower(): value for key, value in response.getheaders()}
        except (OSError, http.client.HTTPException) as error:
            raise HelperError(f"Could not reach the R1 at {self.origin} ({type(error).__name__}).") from None
        finally:
            connection.close()
        try:
            value = json.loads(raw or b"{}")
        except ValueError:
            value = {"error": {"message": raw[:200].decode("utf-8", "replace")}}
        return response.status, response_headers, value if isinstance(value, dict) else {}

    def pair(self, code: str) -> None:
        status, headers, value = self.request("POST", "/v1/management/pair", {"code": code})
        if status != 200:
            raise HelperError(f"Pairing failed: {_message(value)}")
        cookie = headers.get("set-cookie", "")
        self.cookie = cookie.split(";", 1)[0]
        self.csrf = str(value.get("csrfToken", ""))
        if not self.cookie.startswith("sam_session=") or not self.csrf:
            raise HelperError("Pairing returned no session.")


def _message(value: dict[str, object]) -> str:
    error = value.get("error")
    if isinstance(error, dict):
        return str(error.get("message") or error.get("code") or "unknown error")
    return "unknown error"


# --------------------------------------------------------------------------- loopback listener

def _page(ok: bool, text: str) -> bytes:
    accent = "#79f2dd" if ok else "#ff8f8f"
    return (
        "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
        "<title>SamRabbit · Heptabase</title><body style='margin:0;min-height:100vh;display:grid;place-items:center;"
        "background:#07090f;color:#e8eef7;font:16px/1.5 -apple-system,system-ui,sans-serif'><main style='max-width:"
        f"420px;padding:32px;text-align:center'><div style='width:84px;height:84px;margin:0 auto 24px;border-radius:50%;"
        f"background:radial-gradient(circle at 35% 30%,#fff8,{accent} 45%,#0000 72%);box-shadow:0 0 60px {accent}55'>"
        f"</div><h1 style='margin:0 0 8px;font-weight:500;font-size:1.4rem'>{'Connected' if ok else 'Not connected'}"
        f"</h1><p style='margin:0;color:#9fb0c6'>{html.escape(text)}</p></main>"
    ).encode()


def listen_for_callback(redirect_uri: str, expected_state: str, client: R1Client, timeout: float) -> tuple[bool, str]:
    parsed = urlsplit(redirect_uri)
    if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost") or not parsed.port:
        raise HelperError("The R1 did not return a loopback redirect URI.")
    outcome: dict[str, object] = {}
    done = threading.Event()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_args) -> None:
            return

        def do_GET(self) -> None:  # noqa: N802
            target = urlsplit(self.path)
            if target.path != parsed.path:
                self.send_error(404)
                return
            params = {key: values[0] for key, values in parse_qs(target.query).items() if values}
            if params.get("state") != expected_state:
                self._reply(400, False, "This callback does not belong to the current R1 connection attempt.")
                return
            forward = {key: params[key] for key in ("code", "state", "iss", "error") if key in params}
            try:
                status, _, value = client.request("POST", "/v1/heptabase/oauth/callback", forward)
            except HelperError as error:
                status, value = 0, {"error": {"message": str(error)}}
            ok = status == 200 and value.get("connected") is True
            text = ("Heptabase is connected to your R1. You can close this tab." if ok
                    else f"The R1 could not finish connecting: {_message(value)}")
            outcome.update(ok=ok, text=text)
            self._reply(200 if ok else 502, ok, text)
            done.set()

        def _reply(self, status: int, ok: bool, text: str) -> None:
            body = _page(ok, text)
            self.send_response(status)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

    try:
        server = http.server.ThreadingHTTPServer(("127.0.0.1", parsed.port), Handler)
    except OSError as error:
        raise HelperError(f"Port {parsed.port} on this Mac is busy ({error.strerror}).") from None
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.2}, daemon=True)
    thread.start()
    listen_for_callback.ready.set()  # type: ignore[attr-defined]
    try:
        if not done.wait(timeout):
            return False, "Timed out waiting for Heptabase. Run the helper again when you are ready to click Allow."
        return bool(outcome.get("ok")), str(outcome.get("text"))
    finally:
        server.shutdown()
        server.server_close()


listen_for_callback.ready = threading.Event()  # type: ignore[attr-defined]


# --------------------------------------------------------------------------- main

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Connect the R1 Heptabase journal from this Mac.")
    parser.add_argument("--r1", required=True, help="R1 management origin, e.g. https://192.168.1.20:8443")
    auth = parser.add_mutually_exclusive_group(required=True)
    auth.add_argument("--pairing-code", help="six-digit code shown on the R1 (pairs a new session)")
    auth.add_argument("--cookie", help="existing paired session cookie, 'sam_session=…'")
    parser.add_argument("--csrf", help="CSRF token of the existing session (with --cookie)")
    parser.add_argument("--browser", default=DEFAULT_BROWSER, help=f"command to open the URL (default: {DEFAULT_BROWSER})")
    parser.add_argument("--print-url", action="store_true", help="print the full authorization URL (contains the one-time state)")
    parser.add_argument("--no-browser", action="store_true", help="do not open a browser; open the printed URL yourself")
    parser.add_argument("--timeout", type=float, default=300.0, help="seconds to wait for Allow (default 300)")
    parser.add_argument("--pin-file", type=Path, default=DEFAULT_PIN_FILE, help="where the R1 certificate pin is kept")
    parser.add_argument("--repin", action="store_true", help="accept and pin a changed R1 certificate")
    parser.add_argument("--insecure", action="store_true", help="skip TLS verification of the R1 (see module docs)")
    parser.add_argument("--reregister", action="store_true", help="make the R1 register a fresh Heptabase client")
    args = parser.parse_args(argv)
    if args.cookie and not args.csrf:
        parser.error("--cookie requires --csrf")

    try:
        client = R1Client(args.r1, pin_file=None if args.insecure else args.pin_file, insecure=args.insecure,
                          repin=args.repin)
        if args.pairing_code:
            client.pair(args.pairing_code.strip())
            print("Paired with the R1.")
        else:
            client.cookie, client.csrf = args.cookie.strip(), args.csrf.strip()
        status, _, started = client.request("POST", "/v1/management/heptabase/connect/start",
                                            {"redirect": "loopback", "reregister": bool(args.reregister)},
                                            session=True)
        if status != 200:
            raise HelperError(f"The R1 could not start the connection: {_message(started)}")
        url = str(started.get("authorizationUrl", ""))
        redirect_uri = str(started.get("redirectUri", ""))
        state = {key: values[0] for key, values in parse_qs(urlsplit(url).query).items()}.get("state", "")
        if not url.startswith("https://") and not os.environ.get("SAMRABBIT_HELPER_ALLOW_HTTP_AUTH"):
            raise HelperError("The R1 returned an unexpected authorization URL.")
        if not state:
            raise HelperError("The authorization URL has no state.")

        result: dict[str, object] = {}

        def run_listener() -> None:
            try:
                result["value"] = listen_for_callback(redirect_uri, state, client, args.timeout)
            except HelperError as error:
                result["value"] = (False, str(error))
                listen_for_callback.ready.set()  # type: ignore[attr-defined]

        listener = threading.Thread(target=run_listener, daemon=True)
        listener.start()
        listen_for_callback.ready.wait(10)  # type: ignore[attr-defined]
        if "value" in result:
            raise HelperError(str(result["value"][1]))  # type: ignore[index]
        print(f"Listening on {redirect_uri} for Heptabase's redirect.")
        if args.print_url or args.no_browser:
            print(url)
        else:
            print(f"Opening Heptabase's Allow screen ({urlsplit(url).netloc}). Click Allow to connect the R1.")
        if not args.no_browser:
            subprocess.run([*shlex.split(args.browser), url], check=False)
        listener.join(args.timeout + 15)
        ok, text = result.get("value", (False, "No result."))  # type: ignore[misc]
        print(text)
        return 0 if ok else 1
    except HelperError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    socket.setdefaulttimeout(None)
    sys.exit(main())

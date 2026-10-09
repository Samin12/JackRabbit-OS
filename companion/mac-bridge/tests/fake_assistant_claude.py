"""A stand-in for the headless Claude Code CLI as the watch assistant runs it (tests only).

    fake_assistant_claude.py --state <dir> -p --model M --output-format stream-json --verbose ... \\
        (--session-id <uuid> | --resume <uuid>)

It behaves like the real CLI where the bridge depends on it:

* sessions: ``--session-id`` of an existing session fails ("Session ID ... is already in use", exit 1); ``--resume``
  of an unknown one fails ("No conversation found with session ID: ...", exit 1). Like the real CLI 2.1.295 (probed),
  the message is on stderr and in a result event on stdout (``{"type": "result", "subtype":
  "error_during_execution", "is_error": true, "errors": [message]}``). Sessions are files in ``<dir>/sessions``;
* tools: it starts the MCP server from ``--mcp-config`` exactly as configured (command, args, env, plus
  ``CLAUDE_CODE_SESSION_ID``), speaks the protocol the real CLI speaks (``server/discover`` first, then ``initialize``,
  ``notifications/initialized``, ``tools/list``, ``tools/call``) and puts the real results into its stream-json;
* output: ``system/init`` (with ``mcp_servers`` status), ``assistant`` tool_use, ``user`` tool_result (images as
  Claude's ``{type: image, source: {type: base64, media_type, data}}``), ``assistant`` text, ``result``.

What each call does comes from ``<dir>/script.json``: a list of steps, one per call (the last one repeats):
``{"tools": [{"name", "arguments"}], "reply": str, "sleep": s, "child": bool, "error": "busy" | "signed_out" |
"max_turns" | "model" | "crash", "lose_sessions": bool, "mcp": "down"}``. ``child`` starts a ``sleep 60`` in this
process's group first (its pid in ``<dir>/child.pid``), so a test can see the whole group was killed.

Every call is appended to ``<dir>/calls.jsonl``: its arguments, working folder (and what is in it), environment,
stdin, the MCP config's mode, the system prompt, and the MCP exchange.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import queue
import shutil
import stat
import subprocess
import sys
import threading
import time
from typing import Any, Dict, List, Optional

VALUE_FLAGS = {"--model", "--output-format", "--setting-sources", "--mcp-config", "--tools", "--allowedTools",
               "--permission-mode", "--max-turns", "--system-prompt-file", "--session-id", "--resume",
               "--input-format", "--system-prompt"}


def parse(args: List[str]) -> Dict[str, Any]:
    options: Dict[str, Any] = {"flags": []}
    index = 0
    while index < len(args):
        arg = args[index]
        if arg in VALUE_FLAGS and index + 1 < len(args):
            options[arg] = args[index + 1]
            index += 2
            continue
        options["flags"].append(arg)
        index += 1
    return options


def emit(value: Dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(value) + "\n")
    sys.stdout.flush()


class Mcp:
    """The configured MCP server, driven like the real CLI drives it."""

    def __init__(self, config_path: str, session: str) -> None:
        with open(config_path) as handle:
            config = json.load(handle)
        server = config["mcpServers"]["samrabbit"]
        env = dict(os.environ)
        env.update(server.get("env") or {})
        env["CLAUDE_CODE_SESSION_ID"] = session
        self.process = subprocess.Popen([server["command"], *server.get("args", [])], stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env)
        self.lines: "queue.Queue[Optional[bytes]]" = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()
        self.log: Dict[str, Any] = {"calls": []}

    def _read(self) -> None:
        for raw in iter(self.process.stdout.readline, b""):  # type: ignore[union-attr]
            self.lines.put(raw)
        self.lines.put(None)

    def send(self, message: Dict[str, Any]) -> None:
        self.process.stdin.write((json.dumps(message) + "\n").encode())  # type: ignore[union-attr]
        self.process.stdin.flush()  # type: ignore[union-attr]

    def request(self, message: Dict[str, Any], timeout: float = 30.0) -> Dict[str, Any]:
        self.send(message)
        raw = self.lines.get(timeout=timeout)
        if raw is None:
            raise RuntimeError("the MCP server closed its output")
        return json.loads(raw)

    def start(self) -> List[str]:
        started = time.monotonic()
        discover = self.request({"jsonrpc": "2.0", "id": "server-discover-probe-1", "method": "server/discover",
                                 "params": {}}, timeout=5.0)
        self.log["discover"] = discover
        self.log["discoverMs"] = int((time.monotonic() - started) * 1000)
        initialize = self.request({"jsonrpc": "2.0", "id": 0, "method": "initialize", "params": {
            "protocolVersion": "2025-11-25", "capabilities": {"roots": {}, "elicitation": {}},
            "clientInfo": {"name": "claude-code", "version": "2.1.295"}}})
        self.log["initialize"] = initialize
        self.send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        listed = self.request({"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}})
        names = [tool["name"] for tool in listed["result"]["tools"]]
        self.log["tools"] = names
        self.log["schemasOk"] = all(isinstance(tool.get("inputSchema"), dict) and tool.get("description")
                                    for tool in listed["result"]["tools"])
        return names

    def call(self, number: int, name: str, arguments: Dict[str, Any]) -> Dict[str, Any]:
        answer = self.request({"jsonrpc": "2.0", "id": 10 + number, "method": "tools/call",
                               "params": {"name": name, "arguments": arguments}}, timeout=40.0)
        result = answer.get("result") or {}
        self.log["calls"].append({"name": name, "isError": result.get("isError"),
                                  "types": [block.get("type") for block in result.get("content") or []],
                                  "mimeTypes": [block.get("mimeType") for block in result.get("content") or []
                                                if block.get("type") == "image"],
                                  "text": "\n".join(block.get("text", "") for block in result.get("content") or []
                                                    if block.get("type") == "text")})
        return result

    def close(self) -> None:
        try:
            self.process.kill()  # the real CLI kills its MCP servers by signal too (no clean shutdown)
        except OSError:
            pass
        self.process.wait()


def claude_content(blocks: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    out = []
    for block in blocks:
        if block.get("type") == "image":
            out.append({"type": "image", "source": {"type": "base64", "media_type": block.get("mimeType"),
                                                    "data": block.get("data")}})
        else:
            out.append({"type": "text", "text": block.get("text", "")})
    return out


def main() -> int:
    state = Path(sys.argv[sys.argv.index("--state") + 1])
    args = sys.argv[sys.argv.index("--state") + 2:]
    options = parse(args)
    stdin = sys.stdin.read()
    calls_file = state / "calls.jsonl"
    count = len(calls_file.read_text().splitlines()) if calls_file.exists() else 0
    try:
        steps = json.loads((state / "script.json").read_text())
    except (OSError, ValueError):
        steps = [{"reply": "Okay."}]
    step = steps[min(count, len(steps) - 1)] if steps else {"reply": "Okay."}
    mcp_path = options.get("--mcp-config", "")
    prompt_path = options.get("--system-prompt-file", "")
    record: Dict[str, Any] = {
        "args": args, "cwd": os.getcwd(), "cwdEntries": sorted(os.listdir(".")), "envKeys": sorted(os.environ),
        "env": {key: os.environ.get(key) for key in ("PATH", "HOME", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC",
                                                     "CLAUDE_CODE_DISABLE_AUTO_MEMORY", "DISABLE_AUTOUPDATER")},
        "stdin": stdin, "model": options.get("--model"),
        "session": {"new": options.get("--session-id"), "resume": options.get("--resume")},
        "mcpMode": stat.S_IMODE(os.stat(mcp_path).st_mode) if mcp_path and os.path.exists(mcp_path) else None,
        "mcpConfig": json.loads(Path(mcp_path).read_text()) if mcp_path and os.path.exists(mcp_path) else None,
        "systemPrompt": Path(prompt_path).read_text() if prompt_path and os.path.exists(prompt_path) else None,
        "pid": os.getpid(),
    }
    sessions = state / "sessions"
    sessions.mkdir(exist_ok=True)
    if step.get("lose_sessions"):
        shutil.rmtree(sessions)
        sessions.mkdir()
    session = options.get("--session-id") or options.get("--resume") or ""
    path = sessions / session

    def save() -> None:
        with calls_file.open("a") as handle:
            handle.write(json.dumps(record) + "\n")

    def session_error(message: str) -> int:
        emit({"type": "result", "subtype": "error_during_execution", "duration_ms": 0, "is_error": True,
              "num_turns": 0, "session_id": session, "errors": [message]})
        sys.stderr.write(message + "\n")
        return 1

    if options.get("--session-id") and path.exists():
        record["outcome"] = "in_use"
        save()
        return session_error(f"Error: Session ID {session} is already in use.")
    if options.get("--resume") and not path.exists():
        record["outcome"] = "missing"
        save()
        return session_error(f"No conversation found with session ID: {session}")
    if step.get("error") == "signed_out":
        record["outcome"] = "signed_out"
        save()
        sys.stderr.write("Not logged in · Please run /login\n")
        return 1
    if step.get("error") == "crash":
        record["outcome"] = "crash"
        save()
        return 1
    with path.open("a") as handle:
        handle.write(json.dumps({"stdin": stdin}) + "\n")
    tools = step.get("tools") or []
    mcp: Optional[Mcp] = None
    names: List[str] = []
    status = "connected"
    if step.get("mcp") == "down":
        status = "failed"
    else:
        try:
            mcp = Mcp(mcp_path, session)
            names = mcp.start()
        except Exception as error:  # noqa: BLE001
            status = "failed"
            record["mcpError"] = repr(error)
    emit({"type": "system", "subtype": "init", "session_id": session, "cwd": os.getcwd(),
          "tools": ["mcp__samrabbit__" + name for name in names],
          "mcp_servers": [{"name": "samrabbit", "status": status}], "model": options.get("--model"),
          "permissionMode": options.get("--permission-mode")})
    if step.get("child"):
        child = subprocess.Popen(["/bin/sleep", "60"])
        (state / "child.pid").write_text(str(child.pid))
    try:
        for number, tool in enumerate(tools):
            if mcp is None:
                break
            use_id = f"toolu_fake_{count}_{number}"
            emit({"type": "assistant", "session_id": session, "message": {"role": "assistant", "content": [
                {"type": "tool_use", "id": use_id, "name": "mcp__samrabbit__" + tool["name"],
                 "input": tool.get("arguments") or {}}]}})
            result = mcp.call(number, tool["name"], tool.get("arguments") or {})
            emit({"type": "user", "session_id": session, "message": {"role": "user", "content": [
                {"type": "tool_result", "tool_use_id": use_id, "content": claude_content(result.get("content") or []),
                 "is_error": bool(result.get("isError"))}]}})
    finally:
        if mcp is not None:
            record["mcp"] = mcp.log
            mcp.close()
    record["outcome"] = "ran"
    save()
    if step.get("sleep"):
        time.sleep(float(step["sleep"]))
    error = step.get("error")
    reply = str(step.get("reply") or "")
    if error == "busy":
        emit({"type": "result", "subtype": "success", "is_error": True, "api_error_status": 529,
              "result": "Overloaded", "session_id": session, "num_turns": 1})
        return 0
    if error == "model":
        emit({"type": "result", "subtype": "success", "is_error": True, "api_error_status": 404,
              "result": "model not found", "session_id": session, "num_turns": 1})
        return 0
    if error == "max_turns":
        emit({"type": "result", "subtype": "error_max_turns", "is_error": True, "result": "",
              "session_id": session, "num_turns": 6})
        return 1
    emit({"type": "assistant", "session_id": session, "message": {"role": "assistant", "content": [
        {"type": "text", "text": reply}]}})
    emit({"type": "result", "subtype": "success", "is_error": False, "result": reply, "session_id": session,
          "num_turns": len(tools) + 1, "duration_ms": 12})
    return 0


if __name__ == "__main__":
    sys.exit(main())

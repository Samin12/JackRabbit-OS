# On-device runtime

This directory owns trusted Python runtime behavior. Android process lifecycle and Keystore bridging live in `android/runtime-host`; Android presentation code communicates with the runtime through the local API.

Dependency direction:

```text
entrypoint -> application -> api + agents + providers + MCP + storage
api + agents + providers + MCP -> storage interfaces
storage -> Python standard library SQLite
```

Module ownership:

- `api/http_server.py` — transport only (bind, threads, bearer auth, request/response mechanics). Routes live in `api/routes.py` (`RuntimeRoutes`); new endpoints are added there.
- `providers/openai/access.py` — the single access-path → credential/base-URL decision every OpenAI consumer (agents, embeddings, future agents) must use.
- `agents/sdk_runner.py` — the single Agents SDK execution path every agent runner uses.
- `domains/conversation_sync/` — the live mirror of every voice conversation to the SamRabbit app on the user's Mac: device-only `POST /v1/voice/conversation/events` and `/blobs` (from the R1 app), the `ToolCatalog` observer (`tool.completed`), `session.finalized` after finalize, and the `sam-conversation-sync` outbox worker that posts to the Mac bridge's `/v1/sync/*` (backoff 2/5/15/60/300 s; the bridge dedupes by event id). Observe-only: a sync failure never affects voice, tools or finalize. Bridge side: `companion/mac-bridge/README.md` ("Conversation sync").

Run the host-side lifecycle tests:

```bash
PYTHONPATH=runtime python3.13 -m unittest discover -s tests/runtime
```

The runtime is intentionally small. Additional domains enter this package only when their real device-facing behavior and tests are implemented.

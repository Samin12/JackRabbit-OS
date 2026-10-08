"""Voice tool ``ui_generate`` (tool set ``genui_mac``): a real visual (chart, diagram, dashboard, explainer)
designed by Claude Code on the user's Mac and shown on the R1.

Visible only while the Mac bridge is configured. The call returns at once with ``{artifactId, status:
"generating", say}``; ``ArtifactWatcher`` announces ``ui.generated`` when the picture is ready.

Conversation id: the R1's ``conversationId`` for this voice session, so the generated UI lands in the same
timeline on the desktop. In order: a ``conversation_id`` on the tool context (if the runtime provides one),
the injected ``conversation_lookup(voiceSessionId)`` (the conversation-sync map), else the voice session id.
"""

from __future__ import annotations

from collections.abc import Callable
import json
import uuid

from sam_runtime.agents import AudienceResource, AudienceResourceKind
from sam_runtime.tools import ToolCatalog, ToolDefinition, ToolInvocationResult
from sam_runtime.tools.definitions import ToolInvocationContext

from .client import GeneratedUiClient, GeneratedUiFailure, safe_id
from .watcher import ArtifactWatcher

GENERATED_UI_TOOL_SET = AudienceResource(AudienceResourceKind.DOMAIN_TOOL_SET, "genui_mac")
MAX_REQUEST_CHARS = 4000
MAX_DATA_CHARS = 24000
SAY = "Drawing that now on the Mac; it will pop up in a few seconds."

UI_GENERATE_SPEC: tuple[str, str, dict[str, object]] = (
    "ui_generate",
    "Make a real visual and show it on the R1 screen (and live in the desktop app): a chart or graph, a "
    "diagram or flow, a small dashboard, a timeline, a comparison, or a visual explainer. Claude designs it on "
    "the user's Mac and it arrives as a picture in about 10 to 40 seconds. Use it whenever the user asks to see, "
    "chart, graph, plot, draw, map out or visualize something, or when numbers or a structure are much clearer "
    "as a picture. For a quick small card (a timer, a short list, a status) use show_card instead. request: what "
    "to make, in the user's words, made specific. data: every fact and number the visual needs (from the "
    "conversation or tool results), as plain text or JSON; the designer knows nothing else. Returns at once: say "
    "the short 'say' line, keep the conversation going, and describe the picture briefly when the [Generated UI] "
    "message with the image arrives.",
    {
        "type": "object",
        "properties": {
            "request": {"type": "string", "description": "What to make, e.g. 'a bar chart of my meetings this week'."},
            "data": {"type": "string", "description": "The facts and numbers to show, e.g. 'Mon 3, Tue 5, Wed 2'."},
        },
        "required": ["request"],
        "additionalProperties": False,
    },
)


def register_generated_ui_tools(catalog: ToolCatalog, client: GeneratedUiClient, watcher: ArtifactWatcher, *,
                                conversation_lookup: Callable[[str], str | None] | None = None
                                ) -> "GeneratedUiToolHandlers":
    handlers = GeneratedUiToolHandlers(client, watcher, conversation_lookup=conversation_lookup)
    name, description, schema = UI_GENERATE_SPEC
    catalog.register(ToolDefinition(
        tool_id="builtin.genui-mac.ui-generate.v1",
        name=name,
        description=description,
        input_schema=schema,
        handler=lambda arguments: handlers.generate(arguments, None),
        context_handler=lambda context, arguments: handlers.generate(arguments, context),
        effect_class="external_write",
        audience_resource=GENERATED_UI_TOOL_SET,
        available_to=lambda _agent: client.configured(),
    ))
    return handlers


class GeneratedUiToolHandlers:
    def __init__(self, client: GeneratedUiClient, watcher: ArtifactWatcher, *,
                 conversation_lookup: Callable[[str], str | None] | None = None) -> None:
        self._client = client
        self._watcher = watcher
        self._conversation_lookup = conversation_lookup

    def conversation_id(self, context: ToolInvocationContext | None) -> str | None:
        if context is None:
            return None
        explicit = getattr(context, "conversation_id", None)
        if isinstance(explicit, str) and explicit.strip():
            return explicit.strip()
        session = context.voice_session_id
        if session and self._conversation_lookup is not None:
            try:
                mapped = self._conversation_lookup(session)
            except Exception:  # noqa: BLE001 - the mapping is optional
                mapped = None
            if isinstance(mapped, str) and mapped.strip():
                return mapped.strip()
        return session or None

    def generate(self, arguments: dict[str, object], context: ToolInvocationContext | None) -> ToolInvocationResult:
        request = " ".join(str(arguments.get("request") or "").split())
        if not request:
            return _error("invalid_request", "Say what the visual should show.")
        if len(request) > MAX_REQUEST_CHARS:
            return _error("invalid_request", "That request is too long; keep it to a few sentences.")
        data = arguments.get("data")
        data = data.strip() if isinstance(data, str) and data.strip() else None
        if data is not None and len(data) > MAX_DATA_CHARS:
            data = data[:MAX_DATA_CHARS]
        session = context.voice_session_id if context is not None else None
        call = context.tool_call_id if context is not None else None
        # Stable per tool call, so a retried call never makes a second visual.
        request_id = f"rt:{session}:{call}" if session and call else "rt:" + uuid.uuid4().hex
        conversation_id = self.conversation_id(context)
        try:
            result = self._client.generate(request_id=safe_id(request_id), prompt=request, data=data,
                                           conversation_id=conversation_id)
        except GeneratedUiFailure as failure:
            return _error(failure.code, failure.message)
        artifact_id = str(result["artifactId"])
        status = str(result.get("status") or "generating")
        self._watcher.track(artifact_id, conversation_id=conversation_id, voice_session_id=session)
        payload: dict[str, object] = {"ok": True, "artifactId": artifact_id, "status": status}
        if status == "ready":
            payload["say"] = "That visual is already done; it is on the screen."
        elif status == "failed":
            payload.update({"ok": False, "say": "The Mac could not make that visual."})
        else:
            payload["say"] = SAY
        return ToolInvocationResult(json.dumps(payload, separators=(",", ":"), ensure_ascii=False),
                                    is_error=status == "failed")


def _error(code: str, message: str) -> ToolInvocationResult:
    return ToolInvocationResult(json.dumps({"isError": True, "code": code, "message": message},
                                           separators=(",", ":"), ensure_ascii=False), is_error=True)

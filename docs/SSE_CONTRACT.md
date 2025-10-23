# SSE Contract Guide

This project exposes several streaming endpoints that rely on Server-Sent Events (SSE). The v2 orchestration stream now sends structured planning signals so clients can keep the connection alive even when the model spends extra time deciding whether to call tools.

## Event Types

- `decision/chunk`  
  JSON payload describing the latest planning delta. Example:
  ```json
  {
    "stage": "decision",
    "type": "chunk",
    "data": {
      "choices": [{
        "delta": { "content": "considering tool call…" }
      }]
    }
  }
  ```

- `decision/heartbeat`  
  Sent every 4 seconds until the decision stage finishes. Payload:
  ```json
  {
    "stage": "decision",
    "type": "heartbeat",
    "count": 3,
    "timestamp": 1730000000000
  }
  ```
  Clients should treat this as keep-alive only.

- `decision/error`  
  Indicates the upstream planning request failed and the service will fallback to a direct answer.

- Final answer frames  
  The existing behaviour is unchanged. Depending on the `_delta_text`, `_merge_final`, or `_raw_stream` options you receive plain text deltas or raw JSON from the completion stage.

## Client Handling

1. Subscribe with an SSE-capable client (e.g., `EventSource`, `fetch` + `ReadableStream`, or Reactor `Flux`).
2. Route events by inspecting the parsed JSON:
   - `stage === "decision" && type === "chunk"` → show “planning” UI or logs.
   - `stage === "decision" && type === "heartbeat"` → ignore content but reset idle timers.
   - `stage === "decision" && type === "error"` → surface warning, expect the service to fall back to direct answering.
   - Otherwise treat as completion data (existing behaviour).
3. Stop any “planning” indicators once the first non-decision payload arrives, or when the connection closes.

## Quick Smoke Test

```bash
curl -N -H "Content-Type: application/json" \
  -d '{"userId":"u1","conversationId":"c1","q":"告诉我之前的对话","toolChoice":"auto","_raw_stream":true}' \
  http://localhost:8080/ai/v2/chat/stream
```

You should see alternating `decision` events followed by answer frames instead of the connection idling silently.

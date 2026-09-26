#!/usr/bin/env python3
"""A made-up "Notes" MCP server for testing Anu's MCP connector (ADR-046). Debug builds only.

The emulator reaches it at http://10.0.2.2:8765/mcp. It checks what MCP 2026-07-28 requires of a client
(the version header, Mcp-Method and Mcp-Name matching the body, the _meta fields) and prints every
request, so you can see exactly what Anu sent.

  python3 scripts/dev-mock-mcp.py            # a modern, stateless server
  python3 scripts/dev-mock-mcp.py --legacy   # a 2025-11-25 server with initialize and Mcp-Session-Id
  python3 scripts/dev-mock-mcp.py --legacy --port 8767   # both at once, on two ports

Tools: search_notes (read-only) answers as JSON; add_note (not destructive, so Anu asks first) answers
as an SSE stream with a progress notification before the result.
"""
import json
import sys
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODERN = "2026-07-28"
LEGACY = "2025-11-25"
PORT = int(sys.argv[sys.argv.index("--port") + 1]) if "--port" in sys.argv else 8765
legacy = "--legacy" in sys.argv
sessions = set()
notes = [
    "Groceries: oat milk, eggs, spinach",
    "Ideas: a weekend picnic by the river",
    "Books: The Left Hand of Darkness",
]
TOOLS = [
    {
        "name": "search_notes",
        "title": "Search notes",
        "description": "Find the user's notes that contain some words. An empty query lists every note.",
        "inputSchema": {
            "type": "object",
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "properties": {"query": {"type": "string", "description": "Words to look for"}},
            "required": ["query"],
        },
        "annotations": {"readOnlyHint": True},
    },
    {
        "name": "add_note",
        "title": "Add a note",
        "description": "Add a note to the user's notes.",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string", "description": "The note"}},
            "required": ["text"],
            "additionalProperties": False,
        },
        "annotations": {"readOnlyHint": False, "destructiveHint": False},
    },
]


def rpc_error(code, message, rid=None, data=None):
    error = {"code": code, "message": message}
    if data is not None:
        error["data"] = data
    return {"jsonrpc": "2.0", "id": rid, "error": error}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, body=None, headers=None):
        self.send_response(status)
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        if body is None:
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        data = json.dumps(body).encode()
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def reply_sse(self, messages):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        for message in messages:
            self.wfile.write(f"event: message\ndata: {json.dumps(message)}\n\n".encode())
            self.wfile.flush()

    def do_GET(self):
        self.reply(405)

    def do_POST(self):
        if self.path != "/mcp":
            return self.reply(404)
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        method, rid, params = body.get("method"), body.get("id"), body.get("params") or {}
        shown = {k: self.headers.get(k) for k in ("MCP-Protocol-Version", "Mcp-Method", "Mcp-Name", "Mcp-Session-Id", "Authorization") if self.headers.get(k)}
        if "Authorization" in shown:
            shown["Authorization"] = shown["Authorization"].split(" ")[0] + " …"  # never print a token
        print(f"<- {method} {json.dumps(params.get('name') or params.get('arguments') or '')[:80]} {shown}", flush=True)
        if legacy:
            return self.legacy(method, rid, params)
        if self.headers.get("MCP-Protocol-Version") != MODERN:
            return self.reply(400, rpc_error(-32022, "Unsupported protocol version", rid, {"supported": [MODERN], "requested": self.headers.get("MCP-Protocol-Version")}))
        meta = params.get("_meta") or {}
        if meta.get("io.modelcontextprotocol/protocolVersion") != MODERN or "io.modelcontextprotocol/clientCapabilities" not in meta:
            return self.reply(400, rpc_error(-32020, "Header mismatch: _meta must carry the protocol version and client capabilities", rid))
        if self.headers.get("Mcp-Method") != method:
            return self.reply(400, rpc_error(-32020, "Header mismatch: Mcp-Method", rid))
        if method == "tools/call" and self.headers.get("Mcp-Name") != params.get("name"):
            return self.reply(400, rpc_error(-32020, "Header mismatch: Mcp-Name", rid))
        self.handle_method(method, rid, params, modern=True)

    def legacy(self, method, rid, params):
        if method == "initialize":
            session = str(uuid.uuid4())
            sessions.add(session)
            return self.reply(200, {"jsonrpc": "2.0", "id": rid, "result": {"protocolVersion": LEGACY, "capabilities": {"tools": {}}, "serverInfo": {"name": "mock-notes", "version": "1"}}}, {"Mcp-Session-Id": session})
        if self.headers.get("Mcp-Session-Id") not in sessions:
            return self.reply(400, rpc_error(-32000, "Bad Request: No valid session ID provided"))
        if method == "notifications/initialized":
            return self.reply(202)
        self.handle_method(method, rid, params, modern=False)

    def handle_method(self, method, rid, params, modern):
        extra = {"resultType": "complete"} if modern else {}
        if method == "tools/list":
            cache = {"ttlMs": 300000, "cacheScope": "private"} if modern else {}
            return self.reply(200, {"jsonrpc": "2.0", "id": rid, "result": {**extra, **cache, "tools": TOOLS}})
        if method != "tools/call":
            return self.reply(404, rpc_error(-32601, f"Method not found: {method}", rid))
        name, arguments = params.get("name"), params.get("arguments") or {}
        if name == "search_notes":
            # Loose on purpose, like a real notes app: "grocery list" finds "Groceries: …".
            words = [w[:6] for w in str(arguments.get("query", "")).lower().split() if len(w) > 3 and w not in ("list", "note", "notes", "what", "my")]
            found = [n for n in notes if not words or any(w in n.lower() for w in words)]
            text = "\n".join(found) if found else "No notes match."
            return self.reply(200, {"jsonrpc": "2.0", "id": rid, "result": {**extra, "content": [{"type": "text", "text": text}], "isError": False}})
        if name == "add_note":
            text = str(arguments.get("text", "")).strip()
            if not text:
                result = {**extra, "content": [{"type": "text", "text": "A note needs some text."}], "isError": True}
            else:
                notes.append(text)
                print(f"   added note: {text}", flush=True)
                result = {**extra, "content": [{"type": "text", "text": f"Added the note: {text}"}], "isError": False}
            progress = {"jsonrpc": "2.0", "method": "notifications/progress", "params": {"progress": 1, "total": 1}}
            return self.reply_sse([progress, {"jsonrpc": "2.0", "id": rid, "result": result}])
        return self.reply(200, {"jsonrpc": "2.0", "id": rid, "result": {**extra, "content": [{"type": "text", "text": f"No tool {name}"}], "isError": True}})


if __name__ == "__main__":
    print(f"Mock Notes MCP server ({'legacy ' + LEGACY if legacy else MODERN}) on http://0.0.0.0:{PORT}/mcp", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()

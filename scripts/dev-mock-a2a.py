#!/usr/bin/env python3
"""A made-up "Home Lab" agent for testing Anu's A2A connector (ADR-105). Debug builds only.

It speaks the Agent2Agent protocol: its card at /.well-known/agent-card.json, and JSON-RPC message/send at /a2a.
Nothing in it is real: the lamp, the room and the robot arm are made up. It prints every message, so you can see
exactly what Anu sent.

  python3 scripts/dev-mock-a2a.py                    # the emulator reaches it at http://10.0.2.2:8768
  python3 scripts/dev-mock-a2a.py --port 8769 --host raspberrypi.local   # as another device would name it
  python3 scripts/dev-mock-a2a.py --task             # answers as a task with an artifact, not a message

Add it to Anu (debug builds, from the Mac):
  adb shell am broadcast -n io.github.sandeepvijayarao09.anu.personal/io.github.sandeepvijayarao09.anu.DebugA2a --es add http://10.0.2.2:8768/
Then ask in Anu: "Ask the Home Lab agent what the temperature is".
"""
import json
import sys
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[sys.argv.index("--port") + 1]) if "--port" in sys.argv else 8768
HOST = sys.argv[sys.argv.index("--host") + 1] if "--host" in sys.argv else "10.0.2.2"
AS_TASK = "--task" in sys.argv
state = {"lamp": False}

CARD = {
    "name": "Home Lab",
    "description": "A made-up lab with a desk lamp, a room sensor and a robot arm",
    "url": f"http://{HOST}:{PORT}/a2a",
    "version": "1.0.0",
    "protocolVersion": "0.3.0",
    "capabilities": {"streaming": False},
    "defaultInputModes": ["text/plain"],
    "defaultOutputModes": ["text/plain"],
    "skills": [
        {"id": "room", "name": "Room status", "description": "Temperature and humidity"},
        {"id": "lamp", "name": "Desk lamp", "description": "Turn the desk lamp on or off"},
        {"id": "arm", "name": "Robot arm", "description": "Wave or point"},
    ],
}


def answer(text):
    t = text.lower()
    if "temperature" in t or "room" in t or "humid" in t:
        return "The lab is 21.5 °C, with 41% humidity."
    if "lamp" in t or "light" in t:
        on = " off" not in t and "turn off" not in t
        state["lamp"] = on
        return "The desk lamp is on." if on else "The desk lamp is off."
    if "wave" in t or "arm" in t:
        return "The robot arm waved."
    return "Home Lab can tell you the room's temperature, switch the desk lamp, or wave the robot arm."


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path in ("/.well-known/agent-card.json", "/.well-known/agent.json"):
            print(f"card asked for at {self.path}", flush=True)
            self._send(200, CARD)
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length) or b"{}")
        rid = request.get("id")
        if request.get("method") != "message/send":
            self._send(200, {"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "Method not found"}})
            return
        parts = request.get("params", {}).get("message", {}).get("parts", [])
        text = " ".join(p.get("text", "") for p in parts if p.get("kind", "text") == "text")
        print(f"Anu sent: {text!r}", flush=True)
        said = answer(text)
        print(f"Home Lab answers: {said!r}", flush=True)
        reply = {"kind": "message", "role": "agent", "messageId": str(uuid.uuid4()), "parts": [{"kind": "text", "text": said}]}
        if AS_TASK:
            reply = {
                "kind": "task", "id": str(uuid.uuid4()),
                "status": {"state": "completed", "message": reply},
                "artifacts": [{"artifactId": str(uuid.uuid4()), "parts": [{"kind": "text", "text": f"Lamp is {'on' if state['lamp'] else 'off'}."}]}],
            }
        self._send(200, {"jsonrpc": "2.0", "id": rid, "result": reply})

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"Home Lab (made up) on port {PORT}; its card names {CARD['url']}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()

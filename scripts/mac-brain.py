#!/usr/bin/env python3
"""Gemma on your Mac as Anu's top model (ADR-111): a small gateway in front of Ollama.

Ollama stays on 127.0.0.1, where only this Mac can reach it. The gateway listens on the network, over HTTPS with its
own certificate, and answers only Ollama's chat endpoint, only with a token. The phone trusts exactly this certificate
(by its SHA-256) and keeps the token in its vault. It chooses the model and its context, so the phone can't load
anything else or ask for more memory than the Mac has.

  ollama pull gemma4:26b
  python3 scripts/mac-brain.py                  # prints the pairing code for a phone on the same Wi-Fi
  python3 scripts/mac-brain.py --emulator       # the code for the Android emulator on this Mac (10.0.2.2)
  python3 scripts/mac-brain.py --new-token      # a new token: phones paired before must pair again

In Anu: Settings → Models → Your Mac → paste the pairing code. On the emulator, type it with scripts/emu-type.sh.

It logs one line per request, sizes only: what Anu sends is personal (redacted where it can be, §12.2), so it never
prints a word of it. The certificate, key and token live in ~/.anu/mac-brain, readable by you alone.
"""
import argparse
import http.client
import hmac
import hashlib
import json
import os
import secrets
import socket
import ssl
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = os.path.expanduser("~/.anu/mac-brain")
CERT, KEY, TOKEN = (os.path.join(STATE, name) for name in ("cert.pem", "key.pem", "token"))


def lan_address():
    for interface in ("en0", "en1"):
        out = subprocess.run(["ipconfig", "getifaddr", interface], capture_output=True, text=True).stdout.strip()
        if out:
            return out
    return socket.gethostbyname(socket.gethostname())


def ensure_identity(new_token):
    os.makedirs(STATE, mode=0o700, exist_ok=True)
    if not (os.path.exists(CERT) and os.path.exists(KEY)):
        # Its own certificate: the phone pins it, so no authority is needed. Ten years, so pairing lasts.
        names = f"DNS:{socket.gethostname()},DNS:localhost,IP:127.0.0.1,IP:10.0.2.2"
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
             "-keyout", KEY, "-out", CERT, "-days", "3650", "-subj", "/CN=Anu Mac brain", "-addext", f"subjectAltName={names}"],
            check=True, capture_output=True,
        )
        os.chmod(KEY, 0o600)
    if new_token or not os.path.exists(TOKEN):
        with open(TOKEN, "w") as f:
            f.write(secrets.token_urlsafe(32))
        os.chmod(TOKEN, 0o600)
    der = subprocess.run(["openssl", "x509", "-in", CERT, "-outform", "der"], check=True, capture_output=True).stdout
    with open(TOKEN) as f:
        return hashlib.sha256(der).hexdigest(), f.read().strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=11435)
    parser.add_argument("--model", default="gemma4:26b")
    parser.add_argument("--ctx", type=int, default=32768, help="the model's context in tokens; 32K fits a 24 GB Mac")
    parser.add_argument("--ollama", default="127.0.0.1:11434")
    parser.add_argument("--address", help="the address the phone uses (default: this Mac's Wi-Fi address)")
    parser.add_argument("--emulator", action="store_true", help="pair the Android emulator on this Mac")
    parser.add_argument("--new-token", action="store_true")
    args = parser.parse_args()

    pin, token = ensure_identity(args.new_token)
    address = "10.0.2.2" if args.emulator else (args.address or lan_address())
    ollama_host, ollama_port = args.ollama.split(":")

    class Gateway(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass  # Our own line below, sizes only.

        def authorized(self):
            given = self.headers.get("Authorization", "")
            return hmac.compare_digest(given.encode(), f"Bearer {token}".encode())

        def refuse(self, code, message):
            body = json.dumps({"error": message}).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if not self.authorized():
                return self.refuse(401, "wrong token")
            if self.path != "/anu/ping":
                return self.refuse(404, "not here")
            body = json.dumps({"model": args.model, "context": args.ctx}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            if not self.authorized():
                return self.refuse(401, "wrong token")
            if self.path != "/api/chat":
                return self.refuse(404, "only /api/chat")
            started = time.time()
            request = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
            # The Mac decides what runs on it.
            request["model"] = args.model
            request.setdefault("options", {})["num_ctx"] = args.ctx
            request["keep_alive"] = "30m"
            upstream = http.client.HTTPConnection(ollama_host, int(ollama_port), timeout=600)
            try:
                upstream.request("POST", "/api/chat", json.dumps(request), {"Content-Type": "application/json"})
                response = upstream.getresponse()
            except OSError:
                return self.refuse(502, "Ollama isn't running on the Mac")
            self.send_response(response.status)
            self.send_header("Content-Type", response.getheader("Content-Type", "application/x-ndjson"))
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            sent = 0
            try:
                while chunk := response.read1(65536):
                    self.wfile.write(b"%x\r\n%s\r\n" % (len(chunk), chunk))
                    self.wfile.flush()
                    sent += len(chunk)
                self.wfile.write(b"0\r\n\r\n")
            except (BrokenPipeError, ConnectionResetError):
                pass  # The phone stopped the answer (I7); closing the upstream stops Ollama too.
            finally:
                upstream.close()
            chars = sum(len(m.get("content") or "") for m in request.get("messages", []))
            print(f"{time.strftime('%H:%M:%S')} {self.client_address[0]} chat · {len(request.get('messages', []))} messages, "
                  f"{chars} characters, {len(request.get('tools') or [])} tools → {response.status}, {sent} bytes back "
                  f"in {time.time() - started:.1f} s", flush=True)

    server = ThreadingHTTPServer(("0.0.0.0", args.port), Gateway)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(CERT, KEY)
    server.socket = context.wrap_socket(server.socket, server_side=True)

    print(f"Anu's Mac brain: {args.model}, {args.ctx} tokens of context, on port {args.port}.")
    print("Pairing code (it holds the token: share it only with your own phone):\n")
    print(f"  anu-mac://{address}:{args.port}?pin={pin}&token={token}\n", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()

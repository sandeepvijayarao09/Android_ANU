#!/usr/bin/env python3
"""A made-up shoe store that speaks UCP's REST binding, for testing Anu's shopping connector (ADR-047).
Debug builds only. Nothing here is real, and nothing is ever charged.

The emulator reaches it at http://10.0.2.2:8766 (add that address in Settings, Shopping). It checks the
headers UCP requires (UCP-Agent, Request-Id, Idempotency-Key, a correct Content-Digest), replays a
checkout for a repeated Idempotency-Key, and prints every request.

  python3 scripts/dev-mock-store.py
"""
import base64
import hashlib
import json
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8766
VERSION = "2026-08-25"
CATALOG = [
    {"id": "shoe_blue_runner", "title": "Blue Runner Pro", "description": {"plain": "Light, cushioned road running shoe."}, "price": 12000,
     "sizes": ["9", "10", "11"]},
    {"id": "shoe_trail_blazer", "title": "Trail Blazer GTX", "description": {"plain": "Waterproof trail running shoe."}, "price": 14500,
     "sizes": ["10", "11"]},
    {"id": "shoe_city_walker", "title": "City Walker", "description": {"plain": "Everyday walking shoe."}, "price": 8900, "sizes": []},
    {"id": "shoe_gold_racer", "title": "Gold Racer", "description": {"plain": "Carbon-plated racing shoe."}, "price": 25000, "sizes": ["10"]},
]
checkouts = {}  # idempotency key -> session


def money(amount):
    return {"amount": amount, "currency": "USD"}


def product(item):
    variants = [
        {"id": f"{item['id']}_{size}", "sku": f"{item['id'].upper()}-{size}", "title": f"Size {size}", "price": money(item["price"]), "availability": {"available": size != "11"}}
        for size in item["sizes"]
    ]
    return {
        "id": item["id"], "title": item["title"], "description": item["description"],
        "url": f"http://10.0.2.2:{PORT}/p/{item['id']}",
        "price_range": {"min": money(item["price"]), "max": money(item["price"])},
        "variants": variants,
    }


def find(item_id):
    for item in CATALOG:
        if item_id == item["id"]:
            return item, None
        for size in item["sizes"]:
            if item_id == f"{item['id']}_{size}":
                return item, size
    return None, None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, body, content_type="application/json"):
        data = body.encode() if isinstance(body, str) else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        print(f"<- GET {self.path}", flush=True)
        if self.path == "/.well-known/ucp":
            return self.reply(200, {"ucp": {
                "version": VERSION,
                "services": {"dev.ucp.shopping": [{"version": VERSION, "spec": "https://ucp.dev/specification/overview", "transport": "rest", "endpoint": "/ucp/v1"}]},
                "capabilities": {
                    "dev.ucp.shopping.catalog.search": [{"version": VERSION}],
                    "dev.ucp.shopping.checkout": [{"version": VERSION}],
                },
                "payment_handlers": {},
            }})
        if self.path.startswith("/checkout/"):
            return self.reply(200, f"<html><body style='font-family:sans-serif'><h2>Mock Shoe Store</h2><p>Checkout {self.path.split('/')[-1]}.</p>"
                                   "<p>This is a test page. Nothing is charged.</p></body></html>", "text/html")
        self.reply(404, {"messages": [{"type": "error", "content": "not found"}]})

    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        body = json.loads(raw or b"{}")
        headers = {k: self.headers.get(k) for k in ("UCP-Agent", "Request-Id", "Idempotency-Key", "Content-Digest")}
        print(f"<- POST {self.path} {json.dumps(body)[:160]} {headers}", flush=True)
        if not headers["UCP-Agent"] or not headers["UCP-Agent"].startswith("profile=") or not headers["Request-Id"]:
            return self.reply(400, {"messages": [{"type": "error", "content": "UCP-Agent and Request-Id are required"}]})
        digest = "sha-256=:" + base64.b64encode(hashlib.sha256(raw).digest()).decode() + ":"
        if headers["Content-Digest"] != digest:
            return self.reply(400, {"messages": [{"type": "error", "content": "Content-Digest doesn't match the body"}]})
        if self.path == "/ucp/v1/catalog/search":
            words = [w for w in str(body.get("query", "")).lower().split() if len(w) > 2 and w not in ("shoe", "shoes", "the", "for", "some")]
            limit = int((body.get("pagination") or {}).get("limit", 10))
            max_price = ((body.get("filters") or {}).get("price") or {}).get("max")
            found = [p for p in CATALOG if (not words or any(w in (p["title"] + " " + p["description"]["plain"]).lower() for w in words))
                     and (max_price is None or p["price"] <= max_price)]
            if not found and words:  # a store would rather show something than nothing
                found = [p for p in CATALOG if max_price is None or p["price"] <= max_price]
            return self.reply(200, {"ucp": {"version": VERSION, "capabilities": {"dev.ucp.shopping.catalog.search": [{"version": VERSION}]}},
                                    "products": [product(p) for p in found[:limit]],
                                    "pagination": {"has_next_page": False, "total_count": len(found)}})
        if self.path == "/ucp/v1/checkout-sessions":
            key = headers["Idempotency-Key"]
            if not key:
                return self.reply(400, {"messages": [{"type": "error", "content": "Idempotency-Key is required"}]})
            if key in checkouts:
                print("   replayed the checkout for a repeated Idempotency-Key", flush=True)
                return self.reply(201, checkouts[key])
            lines, messages, total = [], [], 0
            for i, line in enumerate(body.get("line_items", [])):
                item, size = find((line.get("item") or {}).get("id", ""))
                quantity = int(line.get("quantity", 1))
                if item is None:
                    messages.append({"type": "error", "code": "not_found", "content": f"No product {line.get('item')}", "severity": "recoverable"})
                    continue
                title = item["title"] + (f", Size {size}" if size else "")
                lines.append({"id": f"li_{i + 1}", "item": {"id": line["item"]["id"], "title": title, "price": item["price"]}, "quantity": quantity,
                              "totals": [{"type": "subtotal", "amount": item["price"] * quantity}]})
                total += item["price"] * quantity
            messages.append({"type": "warning", "code": "missing", "content": "Add a shipping address to see tax and delivery.", "severity": "recoverable"})
            session_id = "chk_" + uuid.uuid4().hex[:8]
            session = {
                "ucp": {"version": VERSION, "capabilities": {"dev.ucp.shopping.checkout": [{"version": VERSION}]}},
                "id": session_id, "status": "incomplete", "currency": "USD", "line_items": lines,
                "totals": [{"type": "subtotal", "amount": total}, {"type": "total", "amount": total}],
                "messages": messages,
                "continue_url": f"http://10.0.2.2:{PORT}/checkout/{session_id}",
            }
            checkouts[key] = session
            print(f"   opened checkout {session_id}: {[l['item']['title'] for l in lines]} total {total / 100:.2f} USD", flush=True)
            return self.reply(201, session)
        self.reply(404, {"messages": [{"type": "error", "content": "not found"}]})


if __name__ == "__main__":
    print(f"Mock UCP shoe store on http://0.0.0.0:{PORT} (the emulator uses http://10.0.2.2:{PORT})", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()

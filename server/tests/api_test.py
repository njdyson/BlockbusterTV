#!/usr/bin/env python3
"""End-to-end test of the BlockbusterTV API against a running PocketBase.

Usage: python3 server/tests/api_test.py http://127.0.0.1:8090 SUPERUSER_EMAIL SUPERUSER_PASSWORD
Creates a throwaway user, exercises every route, and deletes the user afterwards.
"""
import json
import sys
import urllib.error
import urllib.request
import uuid

BASE, SU_EMAIL, SU_PASS = sys.argv[1:4]


def call(method, path, body=None, token=None, device=None, expect=200):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = token
    if device:
        headers["X-Device-Key"] = device
    req = urllib.request.Request(BASE + path, method=method, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            status, text = res.status, res.read().decode()
    except urllib.error.HTTPError as e:
        status, text = e.code, e.read().decode()
    assert status == expect, f"{method} {path}: expected {expect}, got {status}: {text}"
    return json.loads(text) if text else None


su = call("POST", "/api/collections/_superusers/auth-with-password", {"identity": SU_EMAIL, "password": SU_PASS})["token"]
email = f"test-{uuid.uuid4().hex[:8]}@test.local"
user = call("POST", "/api/collections/users/records",
            {"email": email, "password": "testpass123", "passwordConfirm": "testpass123"}, token=su)
try:
    # No public sign-up.
    call("POST", "/api/collections/users/records",
         {"email": "x" + email, "password": "testpass123", "passwordConfirm": "testpass123"}, expect=403)
    token = call("POST", "/api/collections/users/auth-with-password", {"identity": email, "password": "testpass123"})["token"]

    # Nothing without credentials, and the raw collections are closed.
    call("GET", "/api/bbtv/library", expect=401)
    call("GET", "/api/bbtv/library", device="not-a-key", expect=401)
    call("GET", "/api/collections/libraries/records", token=token, expect=403)

    lib = call("GET", "/api/bbtv/library", token=token)
    assert lib == {"version": 1, "shelves": [], "owned": []}, lib

    # Edits.
    lib = call("POST", "/api/bbtv/op", {"op": "addShelf", "name": "Saturday"}, token=token)
    shelf = lib["created"]
    assert call("POST", "/api/bbtv/op", {"op": "addShelf", "name": "Mine", "id": "abcd1234"}, token=token)["created"] == "abcd1234"
    assert call("POST", "/api/bbtv/op", {"op": "addShelf", "name": "Clash", "id": "abcd1234"}, token=token)["created"] != "abcd1234"
    call("POST", "/api/bbtv/op", {"op": "deleteShelf", "shelf": "abcd1234"}, token=token)
    lib = call("GET", "/api/bbtv/library", token=token)
    call("POST", "/api/bbtv/op", {"op": "deleteShelf", "shelf": lib["shelves"][-1]["id"]}, token=token)
    film = {"jwId": "tm92641", "imdbId": "tt1375666", "title": "Inception", "year": 2010, "poster": None}
    call("POST", "/api/bbtv/op", {"op": "addFilm", "shelf": shelf, "film": film}, token=token)
    call("POST", "/api/bbtv/op", {"op": "addFilm", "shelf": shelf, "film": film}, token=token)  # no duplicate
    call("POST", "/api/bbtv/op", {"op": "addFilm", "shelf": shelf, "film": {**film, "jwId": "tm10", "title": "The Matrix"}}, token=token)
    call("POST", "/api/bbtv/op", {"op": "moveFilm", "shelf": shelf, "jwId": "tm10", "delta": -1}, token=token)
    call("POST", "/api/bbtv/op", {"op": "renameShelf", "shelf": shelf, "name": "Saturday Night"}, token=token)
    call("POST", "/api/bbtv/op", {"op": "setOwned", "jwId": "tm92641", "owned": True}, token=token)
    lib = call("GET", "/api/bbtv/library", token=token)
    assert [f["title"] for f in lib["shelves"][0]["films"]] == ["The Matrix", "Inception"], lib
    assert lib["shelves"][0]["name"] == "Saturday Night" and lib["owned"] == ["tm92641"], lib
    call("POST", "/api/bbtv/op", {"op": "removeFilm", "shelf": "nope", "jwId": "tm10"}, token=token, expect=400)
    call("POST", "/api/bbtv/op", {"op": "explode"}, token=token, expect=400)

    # Import only replaces an existing library when asked.
    other = {"version": 1, "shelves": [{"id": "x1", "name": "Imported", "films": []}], "owned": []}
    assert call("POST", "/api/bbtv/import", {"data": other}, token=token)["shelves"][0]["name"] == "Saturday Night"

    # Search goes through the server to JustWatch.
    results = call("GET", "/api/bbtv/search?q=inception", token=token)
    assert any(r["jwId"] == "tm92641" for r in results), results
    print(f"search ok ({len(results)} results)")

    # Linking a TV.
    start = call("POST", "/api/bbtv/pair/start", {"name": "Living room Fire Stick"})
    code, secret = start["code"], start["secret"]
    assert call("GET", f"/api/bbtv/pair/poll?code={code}&secret={secret}")["status"] == "pending"
    call("GET", f"/api/bbtv/pair/poll?code={code}&secret=wrong", expect=410)
    call("GET", f"/api/bbtv/pair/info?code={code}", expect=401)  # needs a signed-in user
    assert call("GET", f"/api/bbtv/pair/info?code={code.lower()}", token=token)["name"] == "Living room Fire Stick"
    call("POST", "/api/bbtv/pair/approve", {"code": code}, token=token)
    approved = call("GET", f"/api/bbtv/pair/poll?code={code}&secret={secret}")
    assert approved["status"] == "approved" and len(approved["deviceKey"]) == 40, approved
    call("GET", f"/api/bbtv/pair/poll?code={code}&secret={secret}", expect=410)  # the key is handed over once
    key = approved["deviceKey"]

    # The TV reads and edits the same library with its key.
    assert call("GET", "/api/bbtv/library", device=key)["shelves"][0]["name"] == "Saturday Night"
    call("POST", "/api/bbtv/op", {"op": "addShelf", "name": "From the TV"}, device=key)
    assert [s["name"] for s in call("GET", "/api/bbtv/library", token=token)["shelves"]] == ["Saturday Night", "From the TV"]
    call("GET", "/api/bbtv/devices", device=key, expect=401)  # managing TVs needs the user

    devices = call("GET", "/api/bbtv/devices", token=token)
    assert [d["name"] for d in devices] == ["Living room Fire Stick"], devices
    call("POST", "/api/bbtv/devices/remove", {"id": devices[0]["id"]}, token=token)
    call("GET", "/api/bbtv/library", device=key, expect=401)  # unlinked

    # A TV can unlink itself.
    start = call("POST", "/api/bbtv/pair/start", {"name": "Bedroom"})
    call("POST", "/api/bbtv/pair/approve", {"code": start["code"]}, token=token)
    key2 = call("GET", f"/api/bbtv/pair/poll?code={start['code']}&secret={start['secret']}")["deviceKey"]
    call("GET", "/api/bbtv/library", device=key2)
    call("POST", "/api/bbtv/devices/forget", {}, device=key2)
    call("GET", "/api/bbtv/library", device=key2, expect=401)
    assert call("GET", "/api/bbtv/devices", token=token) == []
    print("all API checks passed")
finally:
    call("DELETE", f"/api/collections/users/records/{user['id']}", token=su, expect=204)

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

    # Sets and shuffle.
    lib = call("POST", "/api/bbtv/op", {"op": "addShelf", "name": "Rocky", "id": "rocky000"}, token=token)
    for n in range(1, 6):
        call("POST", "/api/bbtv/op", {"op": "addFilm", "shelf": "rocky000", "film": {"jwId": f"r{n}", "title": f"Rocky {n}"}}, token=token)
    call("POST", "/api/bbtv/op", {"op": "addFilm", "shelf": "rocky000", "film": {"jwId": "other", "title": "Creed"}}, token=token)
    call("POST", "/api/bbtv/op", {"op": "reorderFilms", "shelf": "rocky000", "order": ["r1", "other", "r2", "r3", "r4", "r5"]}, token=token)
    lib = call("POST", "/api/bbtv/op", {"op": "createSet", "shelf": "rocky000", "name": "Rocky 1-4", "films": ["r1", "r2", "r3", "r4", "nope"]}, token=token)
    rocky_set = lib["created"]
    shelf_of = lambda lib: next(s for s in lib["shelves"] if s["id"] == "rocky000")
    sh = shelf_of(lib)
    assert sh["sets"] == [{"id": rocky_set, "name": "Rocky 1-4", "films": ["r1", "r2", "r3", "r4"]}], sh["sets"]
    assert [f["jwId"] for f in sh["films"]] == ["r1", "r2", "r3", "r4", "other", "r5"], sh["films"]  # gathered at r1's place
    lib = call("POST", "/api/bbtv/op", {"op": "addToSet", "shelf": "rocky000", "set": rocky_set, "jwId": "r5"}, token=token)
    assert shelf_of(lib)["sets"][0]["films"] == ["r1", "r2", "r3", "r4", "r5"]
    assert [f["jwId"] for f in shelf_of(lib)["films"]] == ["r1", "r2", "r3", "r4", "r5", "other"]
    call("POST", "/api/bbtv/op", {"op": "renameSet", "shelf": "rocky000", "set": rocky_set, "name": "Rocky 1-5"}, token=token)
    lib = call("POST", "/api/bbtv/op", {"op": "reorderSet", "shelf": "rocky000", "set": rocky_set, "order": ["r5", "r4", "r3", "r2", "r1"]}, token=token)
    assert shelf_of(lib)["sets"][0]["films"] == ["r5", "r4", "r3", "r2", "r1"] and shelf_of(lib)["sets"][0]["name"] == "Rocky 1-5"
    call("POST", "/api/bbtv/op", {"op": "reorderSet", "shelf": "rocky000", "set": rocky_set, "order": ["r1"]}, token=token, expect=400)
    call("POST", "/api/bbtv/op", {"op": "reorderFilms", "shelf": "rocky000", "order": ["r1", "r2"]}, token=token, expect=400)
    lib = call("POST", "/api/bbtv/op", {"op": "removeFromSet", "shelf": "rocky000", "jwId": "r5"}, token=token)
    assert shelf_of(lib)["sets"][0]["films"] == ["r4", "r3", "r2", "r1"]
    lib = call("POST", "/api/bbtv/op", {"op": "removeFilm", "shelf": "rocky000", "jwId": "r4"}, token=token)
    assert shelf_of(lib)["sets"][0]["films"] == ["r3", "r2", "r1"]  # removing a film updates its set
    lib = call("POST", "/api/bbtv/op", {"op": "setShuffle", "shelf": "rocky000", "shuffle": True}, token=token)
    assert shelf_of(lib)["shuffle"] is True
    lib = call("POST", "/api/bbtv/op", {"op": "ungroupSet", "shelf": "rocky000", "set": rocky_set}, token=token)
    assert shelf_of(lib)["sets"] == [] and len(shelf_of(lib)["films"]) == 5
    lib = call("POST", "/api/bbtv/op", {"op": "pinShelf", "shelf": "rocky000", "pinned": True}, token=token)
    assert lib["shelves"][0]["id"] == "rocky000" and shelf_of(lib)["pinned"] is True  # pinning moves it to the top
    lib = call("POST", "/api/bbtv/op", {"op": "pinShelf", "shelf": "rocky000", "pinned": False}, token=token)
    assert shelf_of(lib)["pinned"] is False
    call("POST", "/api/bbtv/op", {"op": "deleteShelf", "shelf": "rocky000"}, token=token)
    print("sets and shuffle ok")

    # Import only replaces an existing library when asked.
    other = {"version": 1, "shelves": [{"id": "x1", "name": "Imported", "films": []}], "owned": []}
    assert call("POST", "/api/bbtv/import", {"data": other}, token=token)["shelves"][0]["name"] == "Saturday Night"

    # Search goes through the server to JustWatch.
    results = call("GET", "/api/bbtv/search?q=inception", token=token)
    assert any(r["jwId"] == "tm92641" for r in results), results
    print(f"search ok ({len(results)} results)")

    # Advanced search: people, a person's films, and combined filters.
    people = call("GET", "/api/bbtv/people?q=christopher%20nolan", token=token)
    nolan = next(p for p in people if p["name"] == "Christopher Nolan")
    films = call("GET", f"/api/bbtv/search?person={nolan['id']}&sort=rating", token=token)
    assert {"Inception", "Interstellar"} <= {f["title"] for f in films}, [f["title"] for f in films]
    eighties = call("GET", "/api/bbtv/search?genres=scf&yearMin=1980&yearMax=1989&minRating=7&services=netflix,prime,disney", token=token)
    assert eighties and all(1980 <= f["year"] <= 1989 and f["rating"] >= 7 for f in eighties), eighties
    assert call("GET", "/api/bbtv/search", token=token) == []  # nothing asked, nothing returned
    film = call("GET", "/api/bbtv/film?id=tm92641", token=token)
    assert film["title"] == "Inception" and film["year"] == 2010 and film["rating"] > 8 and film["synopsis"], film
    assert any(d["name"] == "Christopher Nolan" and d["id"].startswith("tp") for d in film["directors"]), film["directors"]
    assert film["cast"][0]["name"] == "Leonardo DiCaprio" and film["genres"], film["cast"][:2]
    assert film["offers"] and all(o["kind"] in ("watch", "rent", "buy") for o in film["offers"]), film["offers"][:2]
    call("GET", "/api/bbtv/film?id=nonsense", token=token, expect=400)
    print(f"film details ok ({len(film['offers'])} offers, {len(film['cast'])} cast)")
    print(f"advanced search ok (Nolan: {len(films)} films, 80s sci-fi: {len(eighties)})")

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

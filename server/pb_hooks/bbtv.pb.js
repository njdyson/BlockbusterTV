/// <reference path="../pb_data/types.d.ts" />

// BlockbusterTV API. The phone app authenticates with a PocketBase user token
// (Authorization header); linked TVs send their device key (X-Device-Key).

// The caller's shelves and owned films.
routerAdd("GET", "/api/bbtv/library", (e) => {
  const lib = require(`${__hooks}/bbtv_lib.js`)
  return e.json(200, lib.load(lib.caller(e).id))
})

// One edit, e.g. {"op": "addFilm", "shelf": "...", "film": {...}}. Returns the updated library.
routerAdd("POST", "/api/bbtv/op", (e) => {
  const lib = require(`${__hooks}/bbtv_lib.js`)
  const user = lib.caller(e)
  const op = e.requestInfo().body
  const data = lib.load(user.id)
  const created = lib.applyOp(data, op)
  lib.save(user.id, data)
  // addShelf returns the new shelf's ID, so the caller can add a film to it straight away.
  if (created) data.created = created
  return e.json(200, data)
})

// Upload a whole library (a TV's existing shelves when it is first linked, or a backup).
// Only replaces a non-empty library when "replace" is true.
routerAdd("POST", "/api/bbtv/import", (e) => {
  const lib = require(`${__hooks}/bbtv_lib.js`)
  const user = lib.caller(e)
  const body = e.requestInfo().body
  const incoming = body.data || {}
  const current = lib.load(user.id)
  if (current.shelves.length && !body.replace) return e.json(200, current)
  const data = { version: 1, shelves: incoming.shelves || [], owned: incoming.owned || [] }
  return e.json(200, lib.save(user.id, data))
})

// Film search: ?q=&person=&genres=&yearMin=&yearMax=&minRating=&services=&sort= (all optional).
routerAdd("GET", "/api/bbtv/search", (e) => {
  const lib = require(`${__hooks}/bbtv_lib.js`)
  lib.caller(e)
  const q = e.request.url.query()
  const params = {}
  for (const key of ["q", "person", "genres", "yearMin", "yearMax", "minRating", "services", "sort"]) params[key] = q.get(key)
  return e.json(200, lib.search(params))
})

// A film's details, cast and where to watch it: ?id=tm92641
routerAdd("GET", "/api/bbtv/film", (e) => {
  const lib = require(`${__hooks}/bbtv_lib.js`)
  lib.caller(e)
  return e.json(200, lib.filmDetails(e.request.url.query().get("id")))
})

// People matching a name, to search by actor or director: ?q=nolan
routerAdd("GET", "/api/bbtv/people", (e) => {
  const lib = require(`${__hooks}/bbtv_lib.js`)
  lib.caller(e)
  return e.json(200, lib.people(e.request.url.query().get("q")))
})

// --- Linking a TV -----------------------------------------------------------

// Called by the TV: returns a code to show, and a secret only the TV knows for polling.
routerAdd("POST", "/api/bbtv/pair/start", (e) => {
  const lib = require(`${__hooks}/bbtv_lib.js`)
  lib.cleanupPairings()
  const secret = $security.randomString(32)
  const record = new Record($app.findCollectionByNameOrId("pairings"))
  let code = lib.newPairingCode()
  while (lib.findPairing(code)) code = lib.newPairingCode()
  record.set("code", code)
  record.set("secret_hash", $security.sha256(secret))
  record.set("device_name", String(e.requestInfo().body.name || "TV").slice(0, 100))
  record.set("status", "pending")
  $app.save(record)
  return e.json(200, { code, secret, expiresInSeconds: lib.PAIRING_TTL_MS / 1000 })
})

// Polled by the TV until the user approves. Hands over the device key once.
routerAdd("GET", "/api/bbtv/pair/poll", (e) => {
  const lib = require(`${__hooks}/bbtv_lib.js`)
  const q = e.request.url.query()
  const pairing = lib.findPairing(q.get("code"))
  if (!pairing || pairing.getString("secret_hash") !== $security.sha256(q.get("secret") || "")) {
    return e.json(410, { status: "expired" })
  }
  if (pairing.getString("status") !== "approved") return e.json(200, { status: "pending" })
  const deviceKey = pairing.getString("device_key")
  $app.delete(pairing)
  return e.json(200, { status: "approved", deviceKey })
})

// Called by the phone app (signed in) to see which TV a code belongs to.
routerAdd("GET", "/api/bbtv/pair/info", (e) => {
  const lib = require(`${__hooks}/bbtv_lib.js`)
  lib.requireUser(e)
  const pairing = lib.findPairing(e.request.url.query().get("code"))
  if (!pairing || pairing.getString("status") !== "pending") throw new NotFoundError("That code has expired. Show a new one on the TV.")
  return e.json(200, { name: pairing.getString("device_name") })
})

// Called by the phone app (signed in) to link the TV to this account.
routerAdd("POST", "/api/bbtv/pair/approve", (e) => {
  const lib = require(`${__hooks}/bbtv_lib.js`)
  const user = lib.requireUser(e)
  const pairing = lib.findPairing(e.requestInfo().body.code)
  if (!pairing || pairing.getString("status") !== "pending") throw new NotFoundError("That code has expired. Show a new one on the TV.")
  const deviceKey = $security.randomString(40)
  const device = new Record($app.findCollectionByNameOrId("devices"))
  device.set("user", user.id)
  device.set("name", pairing.getString("device_name"))
  device.set("key_hash", $security.sha256(deviceKey))
  device.set("last_seen", new Date().toISOString())
  $app.save(device)
  pairing.set("status", "approved")
  pairing.set("device_key", deviceKey)
  $app.save(pairing)
  return e.json(200, { name: device.getString("name") })
})

// Called by a TV to unlink itself (Settings > Unlink on the TV).
routerAdd("POST", "/api/bbtv/devices/forget", (e) => {
  const key = e.request.header.get("X-Device-Key")
  if (!key) throw new UnauthorizedError("No device key.")
  try {
    $app.delete($app.findFirstRecordByFilter("devices", "key_hash = {:h}", { h: $security.sha256(key) }))
  } catch (_) {}
  return e.json(200, { ok: true })
})

routerAdd("GET", "/api/bbtv/devices", (e) => {
  const lib = require(`${__hooks}/bbtv_lib.js`)
  const user = lib.requireUser(e)
  const devices = $app.findRecordsByFilter("devices", "user = {:u}", "-created", 100, 0, { u: user.id })
  return e.json(200, devices.map((d) => ({
    id: d.id,
    name: d.getString("name"),
    lastSeen: d.getString("last_seen"),
  })))
})

routerAdd("POST", "/api/bbtv/devices/remove", (e) => {
  const lib = require(`${__hooks}/bbtv_lib.js`)
  const user = lib.requireUser(e)
  const device = $app.findRecordById("devices", e.requestInfo().body.id)
  if (device.getString("user") !== user.id) throw new ForbiddenError("Not your TV.")
  $app.delete(device)
  return e.json(200, { ok: true })
})

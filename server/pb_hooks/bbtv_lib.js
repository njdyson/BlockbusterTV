/// <reference path="../pb_data/types.d.ts" />

// Shared helpers for bbtv.pb.js. PocketBase runs each route handler in its own
// context, so handlers load this with require() rather than sharing globals.

const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
const PAIRING_TTL_MS = 10 * 60 * 1000
const JUSTWATCH = "https://apis.justwatch.com/graphql"

/** The signed-in user (phone app token) or the user a linked TV belongs to (X-Device-Key). */
function caller(e) {
  if (e.auth && e.auth.collection().name === "users") return e.auth
  const key = e.request.header.get("X-Device-Key")
  if (key) {
    let device = null
    try {
      device = $app.findFirstRecordByFilter("devices", "key_hash = {:h}", { h: $security.sha256(key) })
    } catch (_) {}
    if (device) {
      // Record when a TV was last seen, at most once an hour.
      const last = device.getDateTime("last_seen")
      if (last.isZero() || Date.now() - last.unix() * 1000 > 3600 * 1000) {
        device.set("last_seen", new Date().toISOString())
        $app.save(device)
      }
      return $app.findRecordById("users", device.getString("user"))
    }
  }
  throw new UnauthorizedError("Sign in, or link this TV again.")
}

function requireUser(e) {
  if (e.auth && e.auth.collection().name === "users") return e.auth
  throw new UnauthorizedError("Sign in first.")
}

function emptyLibrary() {
  return { version: 1, shelves: [], owned: [] }
}

function findLibrary(userId) {
  try {
    return $app.findFirstRecordByFilter("libraries", "user = {:u}", { u: userId })
  } catch (_) {
    return null
  }
}

function load(userId) {
  const record = findLibrary(userId)
  if (!record) return emptyLibrary()
  const data = JSON.parse(record.getString("data") || "null") || emptyLibrary()
  data.shelves = (data.shelves || []).map((shelf) => ({ ...shelf, sets: shelf.sets || [], shuffle: !!shelf.shuffle }))
  data.owned = data.owned || []
  return data
}

function save(userId, data) {
  let record = findLibrary(userId)
  if (!record) {
    record = new Record($app.findCollectionByNameOrId("libraries"))
    record.set("user", userId)
  }
  record.set("data", data)
  $app.save(record)
  return data
}

function randomId() {
  return $security.randomStringWithAlphabet(8, "abcdefghijklmnopqrstuvwxyz0123456789")
}

function move(list, index, delta) {
  const target = index + delta
  if (index < 0 || target < 0 || target >= list.length) return
  list.splice(target, 0, list.splice(index, 1)[0])
}

/** Sets only reference films on their shelf; drop missing ones and empty sets. */
function cleanSets(shelf) {
  const onShelf = new Set(shelf.films.map((f) => f.jwId))
  shelf.sets = (shelf.sets || [])
    .map((set) => ({ ...set, films: set.films.filter((id) => onShelf.has(id)) }))
    .filter((set) => set.films.length > 0)
}

/** A film belongs to at most one set on a shelf. */
function takeOutOfSets(shelf, jwId) {
  for (const set of shelf.sets) set.films = set.films.filter((id) => id !== jwId)
}

/**
 * Keeps a set's films next to each other in the shelf's order, at the position of its
 * first member, so the set shows up as one box in a sensible place.
 */
function gather(shelf, set) {
  const members = new Set(set.films)
  const first = shelf.films.findIndex((f) => members.has(f.jwId))
  if (first < 0) return
  const others = shelf.films.filter((f) => !members.has(f.jwId))
  const before = shelf.films.slice(0, first).filter((f) => !members.has(f.jwId)).length
  const grouped = set.films.map((id) => shelf.films.find((f) => f.jwId === id)).filter(Boolean)
  shelf.films = [...others.slice(0, before), ...grouped, ...others.slice(before)]
}

function newId(wanted, taken) {
  wanted = String(wanted || "")
  return /^[a-z0-9]{8}$/.test(wanted) && !taken.some((x) => x.id === wanted) ? wanted : randomId()
}

/** The edits both apps send, mirroring the TV's ShelfStore. */
function applyOp(data, op) {
  const shelf = () => {
    const found = data.shelves.find((s) => s.id === op.shelf)
    if (!found) throw new BadRequestError("That shelf no longer exists.")
    found.sets = found.sets || []
    return found
  }
  const set = (s) => {
    const found = s.sets.find((x) => x.id === op.set)
    if (!found) throw new BadRequestError("That set no longer exists.")
    return found
  }
  switch (op.op) {
    case "addShelf": {
      // The TV picks the ID so its own copy matches straight away; otherwise make one.
      const wanted = String(op.id || "")
      const id = /^[a-z0-9]{8}$/.test(wanted) && !data.shelves.some((s) => s.id === wanted) ? wanted : randomId()
      data.shelves.push({ id, name: String(op.name || "").trim() || "New shelf", films: [], sets: [], shuffle: false })
      return id
    }
    case "renameShelf": {
      const name = String(op.name || "").trim()
      if (name) shelf().name = name
      return null
    }
    case "deleteShelf":
      data.shelves = data.shelves.filter((s) => s.id !== op.shelf)
      return null
    case "moveShelf":
      move(data.shelves, data.shelves.findIndex((s) => s.id === op.shelf), op.delta | 0)
      return null
    case "addFilm": {
      const f = op.film || {}
      if (!f.jwId) throw new BadRequestError("Missing film.")
      const s = shelf()
      if (!s.films.some((x) => x.jwId === f.jwId)) {
        s.films.push({ jwId: f.jwId, imdbId: f.imdbId || "", title: f.title || "", year: f.year || null, poster: f.poster || null })
      }
      return null
    }
    case "removeFilm": {
      const s = shelf()
      s.films = s.films.filter((x) => x.jwId !== op.jwId)
      cleanSets(s)
      return null
    }
    // Replaces the shelf's order with a rearrangement of the same films.
    case "reorderFilms": {
      const s = shelf()
      const order = Array.isArray(op.order) ? op.order : []
      const current = s.films.map((f) => f.jwId)
      if (order.length !== current.length || [...order].sort().join() !== [...current].sort().join()) {
        throw new BadRequestError("The shelf changed; reload and try again.")
      }
      s.films = order.map((id) => s.films.find((f) => f.jwId === id))
      return null
    }
    case "setShuffle":
      shelf().shuffle = !!op.shuffle
      return null
    // Sets: films on a shelf grouped into one box, e.g. Rocky 1–5. A set keeps its own order.
    case "createSet": {
      const s = shelf()
      const onShelf = new Set(s.films.map((f) => f.jwId))
      const films = (Array.isArray(op.films) ? op.films : []).filter((id, i, all) => onShelf.has(id) && all.indexOf(id) === i)
      if (!films.length) throw new BadRequestError("Choose some films for the set.")
      for (const id of films) takeOutOfSets(s, id)
      const id = newId(op.id, s.sets)
      const created = { id, name: String(op.name || "").trim() || "Box set", films }
      s.sets.push(created)
      cleanSets(s)
      gather(s, created)
      return id
    }
    case "renameSet": {
      const name = String(op.name || "").trim()
      if (name) set(shelf()).name = name
      return null
    }
    case "ungroupSet": {
      const s = shelf()
      s.sets = s.sets.filter((x) => x.id !== op.set)
      return null
    }
    case "addToSet": {
      const s = shelf()
      const target = set(s)
      if (!s.films.some((f) => f.jwId === op.jwId)) throw new BadRequestError("That film isn't on this shelf.")
      takeOutOfSets(s, op.jwId)
      target.films.push(op.jwId)
      cleanSets(s)
      gather(s, target)
      return null
    }
    case "removeFromSet": {
      const s = shelf()
      takeOutOfSets(s, op.jwId)
      cleanSets(s)
      return null
    }
    // A set's own order: a rearrangement of its films.
    case "reorderSet": {
      const s = shelf()
      const target = set(s)
      const order = Array.isArray(op.order) ? op.order : []
      if ([...order].sort().join() !== [...target.films].sort().join()) throw new BadRequestError("The set changed; reload and try again.")
      target.films = order
      gather(s, target)
      return null
    }
    case "moveFilm": {
      const s = shelf()
      move(s.films, s.films.findIndex((x) => x.jwId === op.jwId), op.delta | 0)
      return null
    }
    case "setOwned":
      data.owned = data.owned.filter((id) => id !== op.jwId)
      if (op.owned) data.owned.push(op.jwId)
      data.owned.sort()
      return null
    default:
      throw new BadRequestError("Unknown edit: " + op.op)
  }
}

// Our service IDs (as in the TV's services.json) to JustWatch package short names.
const SERVICE_PACKAGES = {
  netflix: "nfx", prime: "amp", disney: "dnp", appletv: "atp", plex: "plx",
  iplayer: "bbc", itvx: "itv", channel4: "al4", now: "ntv",
}
const SORTS = { popular: "POPULAR", rating: "IMDB_SCORE", newest: "RELEASE_YEAR", az: "ALPHABETICAL" }

function gql(query, variables) {
  const res = $http.send({
    method: "POST",
    url: JUSTWATCH,
    body: JSON.stringify({ query, variables }),
    headers: { "Content-Type": "application/json" },
    timeout: 20,
  })
  if (res.statusCode !== 200 || !res.json || !res.json.data) throw new ApiError(502, "Film search is unavailable right now.")
  return res.json.data
}

/**
 * Film search on JustWatch (GB). Every part is optional and they combine:
 * q (title), person (JustWatch person ID, e.g. "tp1725"), genres (codes like "scf"),
 * yearMin/yearMax, minRating (IMDb), services (our IDs: streaming there now), sort.
 */
function search(params) {
  const p = params || {}
  const filter = { objectTypes: ["MOVIE"] }
  const text = String(p.q || "").trim()
  if (text) filter.searchQuery = text
  if (/^tp\d+$/.test(p.person || "")) filter.personId = p.person
  const genres = String(p.genres || "").split(",").filter((g) => /^[a-z]{3}$/.test(g))
  if (genres.length) filter.genres = genres
  const yearMin = parseInt(p.yearMin, 10), yearMax = parseInt(p.yearMax, 10)
  if (yearMin || yearMax) filter.releaseYear = { min: yearMin || 1900, max: yearMax || 2100 }
  const rating = parseFloat(p.minRating)
  if (rating > 0) filter.imdbScore = { min: rating }
  const packages = String(p.services || "").split(",").map((id) => SERVICE_PACKAGES[id]).filter(Boolean)
  if (packages.length) {
    filter.packages = packages
    filter.monetizationTypes = ["FLATRATE", "FREE", "ADS"]
  }
  if (Object.keys(filter).length === 1) return []
  const query = `query($filter: TitleFilter!, $sort: PopularTitlesSorting!) {
    popularTitles(country: GB, first: 24, sortBy: $sort, filter: $filter) {
      edges { node { id ... on Movie { content(country: GB, language: "en") {
        title originalReleaseYear posterUrl externalIds { imdbId } scoring { imdbScore } } } } } } }`
  const data = gql(query, { filter, sort: SORTS[p.sort] || "POPULAR" })
  return (data.popularTitles.edges || []).map((edge) => {
    const node = edge.node
    const c = node.content || {}
    return {
      jwId: node.id,
      imdbId: (c.externalIds && c.externalIds.imdbId) || "",
      title: c.title || "",
      year: c.originalReleaseYear || null,
      poster: c.posterUrl ? "https://images.justwatch.com" + c.posterUrl.replace("{profile}", "s166").replace("{format}", "jpg") : null,
      rating: (c.scoring && c.scoring.imdbScore) || null,
    }
  })
}

/** People (actors, directors…) matching a name, most popular first. */
function people(text) {
  text = String(text || "").trim()
  if (text.length < 3) return []
  const query = `query($q: String!) { popularPeople(country: GB, first: 6, filter: {searchQuery: $q}) {
    edges { node { id ... on Person { content(country: GB, language: "en") { fullName } } } } } }`
  return (gql(query, { q: text }).popularPeople.edges || [])
    .map((edge) => ({ id: edge.node.id, name: (edge.node.content && edge.node.content.fullName) || "" }))
    .filter((person) => person.name)
}

function newPairingCode() {
  return $security.randomStringWithAlphabet(6, CODE_ALPHABET)
}

/** A pending pairing by code, or null if missing or expired (expired ones are deleted). */
function findPairing(code) {
  let pairing = null
  try {
    pairing = $app.findFirstRecordByFilter("pairings", "code = {:c}", { c: String(code || "").toUpperCase().trim() })
  } catch (_) {
    return null
  }
  if (Date.now() - pairing.getDateTime("created").unix() * 1000 > PAIRING_TTL_MS) {
    $app.delete(pairing)
    return null
  }
  return pairing
}

function cleanupPairings() {
  const cutoff = new Date(Date.now() - PAIRING_TTL_MS).toISOString().replace("T", " ")
  const old = $app.findRecordsByFilter("pairings", "created < {:t}", "", 100, 0, { t: cutoff })
  for (const r of old) $app.delete(r)
}

module.exports = {
  caller, requireUser, load, save, applyOp, search, people, emptyLibrary,
  newPairingCode, findPairing, cleanupPairings, PAIRING_TTL_MS,
}

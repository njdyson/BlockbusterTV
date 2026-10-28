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
  return { version: 1, shelves: [], series: [], owned: [] }
}

/** Film shelves and series shelves are kept apart: series shelves only hold series. */
const isSeriesId = (jwId) => /^ts\d+$/.test(String(jwId || ""))

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
  const clean = (shelf) => ({ ...shelf, sets: shelf.sets || [], shuffle: !!shelf.shuffle, pinned: !!shelf.pinned })
  data.shelves = (data.shelves || []).map(clean)
  data.series = (data.series || []).map(clean)
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

/**
 * The edits both apps send, mirroring the TV's ShelfStore. Shelf IDs are unique across film
 * and series shelves, so every edit but addShelf finds its shelf in whichever list holds it.
 */
function applyOp(data, op) {
  data.series = data.series || []
  const listOf = (id) => (data.series.some((s) => s.id === id) ? "series" : "shelves")
  const shelf = () => {
    const found = data[listOf(op.shelf)].find((s) => s.id === op.shelf)
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
      const taken = [...data.shelves, ...data.series]
      const id = /^[a-z0-9]{8}$/.test(wanted) && !taken.some((s) => s.id === wanted) ? wanted : randomId()
      data[op.kind === "series" ? "series" : "shelves"].push({ id, name: String(op.name || "").trim() || "New shelf", films: [], sets: [], shuffle: false, pinned: false })
      return id
    }
    case "renameShelf": {
      const name = String(op.name || "").trim()
      if (name) shelf().name = name
      return null
    }
    case "deleteShelf":
      data.shelves = data.shelves.filter((s) => s.id !== op.shelf)
      data.series = data.series.filter((s) => s.id !== op.shelf)
      return null
    case "moveShelf": {
      const list = data[listOf(op.shelf)]
      move(list, list.findIndex((s) => s.id === op.shelf), op.delta | 0)
      return null
    }
    case "addFilm": {
      const f = op.film || {}
      if (!f.jwId) throw new BadRequestError("Missing film.")
      const s = shelf()
      if (isSeriesId(f.jwId) !== (listOf(s.id) === "series")) {
        throw new BadRequestError(isSeriesId(f.jwId) ? "Series go on series shelves." : "Films go on film shelves.")
      }
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
    // Pinned shelves show first, in this order, ahead of the shuffled rest. A newly pinned
    // shelf goes to the very top.
    case "pinShelf": {
      const s = shelf()
      s.pinned = !!op.pinned
      const list = listOf(s.id)
      if (s.pinned) data[list] = [s, ...data[list].filter((x) => x !== s)]
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
// JustWatch can't filter on spoken language, so "English" means made in an English-speaking country.
const ENGLISH_COUNTRIES = ["GB", "US", "IE", "CA", "AU", "NZ"]
// Browsing by top rated skips titles with few IMDb votes, which otherwise fill the list with 9.8s from 20 votes.
const TOP_RATED_MIN_VOTES = 10000

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
 * Film search on JustWatch (GB), or series search with type "series". Every other part is
 * optional and they combine: q (title), person (JustWatch person ID, e.g. "tp1725"), genres
 * (codes like "scf"), yearMin/yearMax, minRating (IMDb), services (our IDs: streaming there now),
 * lang ("en": English-speaking countries only), sort.
 */
function search(params) {
  const p = params || {}
  const series = p.type === "series"
  const filter = { objectTypes: [series ? "SHOW" : "MOVIE"] }
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
  if (p.lang === "en") filter.productionCountries = ENGLISH_COUNTRIES
  if (Object.keys(filter).length === 1) return []
  if (p.sort === "rating" && !text && !filter.personId) filter.imdbVotes = { min: TOP_RATED_MIN_VOTES }
  const query = `query($filter: TitleFilter!, $sort: PopularTitlesSorting!) {
    popularTitles(country: GB, first: 24, sortBy: $sort, filter: $filter) {
      edges { node { id ... on ${series ? "Show" : "Movie"} { content(country: GB, language: "en") {
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

const GENRE_NAMES = {
  act: "Action", ani: "Animation", cmy: "Comedy", crm: "Crime", doc: "Documentary", drm: "Drama",
  fnt: "Fantasy", fml: "Family", hst: "History", hrr: "Horror", msc: "Music", rma: "Romance",
  scf: "Sci-Fi", spt: "Sport", trl: "Thriller", war: "War", wsn: "Western", rly: "Reality TV",
}

function jwImage(path, profile, format) {
  return path ? "https://images.justwatch.com" + path.replace("{profile}", profile).replace("{format}", format || "jpg") : null
}

/** "Season 1", "Seasons 1–3", "Seasons 1–2, 5". */
function seasonsLabel(numbers) {
  const ranges = []
  for (const n of numbers) {
    const last = ranges[ranges.length - 1]
    if (last && n === last[1] + 1) last[1] = n
    else ranges.push([n, n])
  }
  const text = ranges.map(([a, b]) => (a === b ? String(a) : `${a}–${b}`)).join(", ")
  return (numbers.length === 1 ? "Season " : "Seasons ") + text
}

const OFFER_KINDS = { FLATRATE: "watch", FREE: "watch", ADS: "watch", RENT: "rent", BUY: "buy" }

/**
 * Everything about one film or series for the phone app's page: details, people, where to
 * watch. For a series, each way to watch says which seasons it has when it isn't all of them.
 */
function filmDetails(id) {
  id = String(id || "")
  const series = /^ts\d+$/.test(id)
  if (!series && !/^tm\d+$/.test(id)) throw new BadRequestError("Unknown film.")
  const offerFields = `monetizationType retailPrice(language: "en") retailPriceValue standardWebURL
      package { technicalName clearName shortName icon }`
  const query = `query($id: ID!) { node(id: $id) { id ... on ${series ? "Show" : "Movie"} {
    content(country: GB, language: "en") {
      title originalReleaseYear shortDescription runtime ageCertification
      genres { shortName } scoring { imdbScore imdbVotes } externalIds { imdbId tmdbId }
      posterUrl backdrops { backdropUrl } credits { role name personId characterName }
    }
    offers(country: GB, platform: WEB) { ${offerFields} }
    ${series ? `totalSeasonCount seasons(sortDirection: ASC) {
      content(country: GB, language: "en") { seasonNumber }
      offers(country: GB, platform: WEB) { monetizationType package { clearName } } }` : ""}
  } } }`
  const node = gql(query, { id }).node
  if (!node || !node.content) throw new NotFoundError(series ? "Series not found." : "Film not found.")
  const c = node.content
  const ours = Object.values(SERVICE_PACKAGES)
  // A series' seasons (specials, season 0, aside) and which kind+provider has each.
  const seasons = (node.seasons || []).filter((season) => season.content && season.content.seasonNumber > 0)
  const seasonsWith = (kind, provider) => seasons
    .filter((season) => (season.offers || []).some((o) => OFFER_KINDS[o.monetizationType] === kind && o.package.clearName === provider))
    .map((season) => season.content.seasonNumber)
  // One entry per provider and kind (JustWatch lists SD, HD and 4K separately), cheapest first.
  const offers = {}
  for (const o of node.offers || []) {
    const kind = OFFER_KINDS[o.monetizationType]
    if (!kind) continue
    // Shops listed without a price sell discs (e.g. Zavvi), not streams.
    if (kind !== "watch" && !o.retailPrice) continue
    const key = kind + ":" + o.package.clearName
    const current = offers[key]
    if (current && (current.priceValue || 0) <= (o.retailPriceValue || 0)) continue
    offers[key] = {
      kind,
      provider: o.package.clearName,
      icon: jwImage(o.package.icon, "s100", "png"),
      price: o.retailPrice || null,
      priceValue: o.retailPriceValue || null,
      url: o.standardWebURL || null,
      mine: ours.includes(o.package.shortName),
      free: o.monetizationType !== "FLATRATE" && kind === "watch",
      seasons: null,
    }
  }
  if (seasons.length > 1) {
    for (const offer of Object.values(offers)) {
      const have = seasonsWith(offer.kind, offer.provider)
      if (have.length && have.length < seasons.length) offer.seasons = seasonsLabel(have)
    }
  }
  const credits = c.credits || []
  const person = (cr) => ({ id: "tp" + cr.personId, name: cr.name, character: cr.characterName || null })
  return {
    jwId: node.id,
    series,
    imdbId: (c.externalIds && c.externalIds.imdbId) || "",
    title: c.title || "",
    year: c.originalReleaseYear || null,
    synopsis: c.shortDescription || "",
    runtime: c.runtime || null,
    seasonCount: series ? node.totalSeasonCount || seasons.length || null : null,
    ageCertification: c.ageCertification || null,
    genres: (c.genres || []).map((g) => GENRE_NAMES[g.shortName]).filter(Boolean),
    rating: (c.scoring && c.scoring.imdbScore) || null,
    votes: (c.scoring && c.scoring.imdbVotes) || null,
    poster: jwImage(c.posterUrl, "s332"),
    backdrop: jwImage(c.backdrops && c.backdrops[0] && c.backdrops[0].backdropUrl, "s1440"),
    // A series' directors are per episode, so only films list them.
    directors: series ? [] : credits.filter((cr) => cr.role === "DIRECTOR").map(person),
    cast: credits.filter((cr) => cr.role === "ACTOR").slice(0, 12).map(person),
    // Your services first, then cheapest.
    offers: Object.values(offers).sort((a, b) => (b.mine - a.mine) || ((a.priceValue || 0) - (b.priceValue || 0))),
  }
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
  caller, requireUser, load, save, applyOp, search, people, filmDetails, emptyLibrary,
  newPairingCode, findPairing, cleanupPairings, PAIRING_TTL_MS,
}

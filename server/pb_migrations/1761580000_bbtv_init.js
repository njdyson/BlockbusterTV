/// <reference path="../pb_data/types.d.ts" />

// BlockbusterTV schema. Everything is reached through the custom routes in
// pb_hooks/bbtv.pb.js, so the collections have no public API rules.
migrate((app) => {
  const users = app.findCollectionByNameOrId("users")
  // Accounts are created by the admin in the dashboard; there is no public sign-up.
  users.createRule = null
  // Keep the phone app signed in for 90 days between uses.
  users.authToken.duration = 90 * 24 * 60 * 60
  app.save(users)

  // One record per user: their shelves and owned films, in the same JSON format
  // the TV app stores locally (ShelvesFile).
  app.save(new Collection({
    type: "base",
    name: "libraries",
    fields: [
      { name: "user", type: "relation", collectionId: users.id, maxSelect: 1, required: true, cascadeDelete: true },
      { name: "data", type: "json", maxSize: 2000000 },
      { name: "updated", type: "autodate", onCreate: true, onUpdate: true },
    ],
    indexes: ["CREATE UNIQUE INDEX idx_libraries_user ON libraries (user)"],
  }))

  // TVs linked to a user. The TV holds the key; only its hash is stored.
  app.save(new Collection({
    type: "base",
    name: "devices",
    fields: [
      { name: "user", type: "relation", collectionId: users.id, maxSelect: 1, required: true, cascadeDelete: true },
      { name: "name", type: "text", max: 100 },
      { name: "key_hash", type: "text", required: true },
      { name: "last_seen", type: "date" },
      { name: "created", type: "autodate", onCreate: true },
    ],
    indexes: ["CREATE UNIQUE INDEX idx_devices_key ON devices (key_hash)"],
  }))

  // Short-lived link requests: the TV shows the code, the user approves it in the app.
  app.save(new Collection({
    type: "base",
    name: "pairings",
    fields: [
      { name: "code", type: "text", required: true },
      { name: "secret_hash", type: "text", required: true },
      { name: "device_name", type: "text", max: 100 },
      { name: "status", type: "text" },
      { name: "device_key", type: "text" },
      { name: "created", type: "autodate", onCreate: true },
    ],
    indexes: ["CREATE UNIQUE INDEX idx_pairings_code ON pairings (code)"],
  }))
}, (app) => {
  for (const name of ["pairings", "devices", "libraries"]) {
    app.delete(app.findCollectionByNameOrId(name))
  }
})

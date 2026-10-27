# BlockbusterTV server

The account server: a [PocketBase](https://pocketbase.io) app (one program, SQLite
database) that holds each user's shelves and owned films, serves the installable
phone app, and lets TVs link to an account and sync.

| Folder | What it is |
|---|---|
| `pb_migrations/` | Database setup: `libraries` (one per user), `devices` (linked TVs), `pairings` (link codes). No public sign-up. |
| `pb_hooks/` | The API under `/api/bbtv/…`: library, edits, film search (JustWatch), linking TVs, managing TVs. |
| `pb_public/` | The phone app (PWA): sign in, search and add films, shelves, linked TVs, backup. |
| `deploy/` | Install script and the nginx snippet for Plesk. |
| `tests/` | `api_test.py`: checks every route against a running server. |

## Setting it up on the Plesk server

You need to run commands as root, either over SSH (`ssh root@185.230.218.116`) or in
Plesk's own terminal (**Tools & Settings > SSH Terminal**, if that extension is installed).

1. **Subdomain:** in Plesk, **Websites & Domains > Add Subdomain**, name `blockbuster`
   under `psybob.uk`. Make sure its DNS record exists (Plesk adds it if it manages the
   DNS for psybob.uk; otherwise add an A record for `blockbuster` pointing at
   185.230.218.116 wherever the DNS is hosted).
2. **HTTPS:** on the subdomain, **SSL/TLS Certificates > Let's Encrypt**, issue a
   certificate, and turn on the redirect from HTTP to HTTPS.
3. **Install PocketBase and the app** (as root):
   ```
   curl -fsSL https://raw.githubusercontent.com/njdyson/BlockbusterTV/link-lab/server/deploy/install.sh -o install.sh
   sudo BRANCH=link-lab bash install.sh
   ```
   It asks for an admin email and password the first time. Re-run it later to update.
   (Once the work is merged into `main`, drop `BRANCH=link-lab` and use `main` in the URL.)
4. **Connect the subdomain to it:** on the subdomain, **Apache & nginx Settings**, paste
   `deploy/nginx-directives.conf` into **Additional nginx directives**, and save.
5. **Check:** https://blockbuster.psybob.uk should show the sign-in page, and
   https://blockbuster.psybob.uk/_/ the PocketBase dashboard.
6. **Create your account:** in the dashboard, **Collections > users > New record**
   (email and password). Do the same for friends. There is no public sign-up.

Recommended in the dashboard (**Settings**):
- **Application:** set the name to BlockbusterTV and the URL to https://blockbuster.psybob.uk.
- **Rate limits:** turn them on (protects sign-in and link codes from guessing).
- **Trusted proxy:** add `X-Real-IP` so rate limits see real IP addresses, not nginx.
- **Backups:** schedule automatic backups of the database.

## Running it locally

Download PocketBase (or build it: `go build github.com/pocketbase/pocketbase/examples/base`),
put the binary next to `pb_hooks`, `pb_migrations` and `pb_public`, then:
```
./pocketbase migrate up
./pocketbase superuser upsert admin@example.com somepassword
./pocketbase serve
python3 tests/api_test.py http://127.0.0.1:8090 admin@example.com somepassword
```
The TV app's server client has a test too (`app/src/test/…/AccountServerTest.kt`):
`BBTV_TEST_SERVER=http://127.0.0.1:8090 BBTV_TEST_ADMIN=… BBTV_TEST_ADMIN_PASSWORD=… ./gradlew testDebugUnitTest`.

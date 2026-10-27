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

Plesk Git keeps the code up to date; PocketBase runs as a background service straight
from the folder Plesk deploys to. After the one-time setup in step 4, each deploy updates
the server by itself: changed API code reloads within seconds and the phone app files
are served as they are. The database lives in a separate folder that deploys never touch.

1. **Subdomain:** **Websites & Domains > Add Subdomain**, `blockbuster` under `psybob.uk`,
   default document root. Make sure DNS has a record for it (automatic if Plesk runs the
   DNS for psybob.uk; otherwise add an A record `blockbuster` → 185.230.218.116).
2. **HTTPS:** on the subdomain, **SSL/TLS Certificates > Let's Encrypt**, issue a
   certificate, and turn on the HTTP to HTTPS redirect.
3. **Git:** on the subdomain, **Git > Add Repository**:
   - Remote Git hosting, URL `https://github.com/njdyson/BlockbusterTV.git` (public, no key needed)
   - Deployment mode: **Automatic**
   - Deployment path: the subdomain's folder (the default) is fine: nginx sends every
     request to PocketBase, so the files are never served directly
   - Then choose the branch **`link-lab`** in the repository's settings.

   To deploy on every push, copy the **Webhook URL** Plesk shows into GitHub: the repo's
   **Settings > Webhooks > Add webhook** (content type `application/json`). Otherwise
   press **Pull Updates** in Plesk when there's something new.
4. **Start the server** (once, as root). Use the folder Plesk deploys to (step 3); if you
   kept the default, that's the subdomain's own folder, as below.
   - **From a phone (no SSH):** **Tools & Settings > Scheduled Tasks > Add Task**, system
     user **root**, task type **Run a command**, with this command (your own email and a new
     password), save it, press **Run Now**, then delete the task (it contains the password):
     ```
     ADMIN_EMAIL='you@example.com' ADMIN_PASSWORD='a-new-password' APP_DIR=/var/www/vhosts/psybob.uk/blockbuster.psybob.uk bash /var/www/vhosts/psybob.uk/blockbuster.psybob.uk/server/deploy/install.sh
     ```
   - **Over SSH** (`ssh root@185.230.218.116`): run the same without the ADMIN_ settings and it
     asks for them: `sudo APP_DIR=… bash …/server/deploy/install.sh`.

   It downloads PocketBase, sets it up as a service that starts with the server, and creates
   the dashboard admin. The database goes in `/var/www/vhosts/psybob.uk/blockbuster-data`.
   (Running it again is safe; it's only needed again if an update says so.)
5. **Connect the subdomain to it:** on the subdomain, **Apache & nginx Settings**, paste
   `deploy/nginx-directives.conf` into **Additional nginx directives**, and save.
6. **Check:** https://blockbuster.psybob.uk shows the sign-in page, and
   https://blockbuster.psybob.uk/_/ the PocketBase dashboard.
7. **Create your account:** in the dashboard, **Collections > users > New record**
   (email and password). Same for friends. There is no public sign-up.

Without Plesk Git, run `install.sh` without `APP_DIR` (add `BRANCH=link-lab`): it
fetches the code from GitHub itself into `/opt/blockbustertv`. Re-run it to update.

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

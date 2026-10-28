# Blomp API — Everything We Learned (TinyVault field notes)

> Written 2026-10-16 after a full day of live debugging against Blomp with real
> credentials. This is the map of how Blomp actually behaves and how TinyVault
> 1.0.4 (Blomp edition) is built around it. Read this before touching the
> storage code again.

---

## 1. What Blomp is

- Blomp = **OpenStack Swift** object storage with a **Keystone** identity service
  in front. No FTP/SFTP/WebDAV/S3. Only the Swift REST API.
- Account = an email + password. That is the ONLY credential anyone needs.
  Free tier: ~20–40 GB ("fucking amount of storage" per the boss). No file-size
  limit claim by Blomp; Swift itself caps a single plain object at 5 GiB.
- Support forum: https://support.blomp.com (BlompSupport replies there).
- ⚠️ Blomp's Swift has had a publicly documented 403-on-storage outage
  (forum thread t=3642, open March 2026). If storage ops 403 for everyone,
  it's them, not you.

## 2. Authentication (how to get in)

- Base auth host: `https://authenticate.blomp.com` (same backend as
  `authenticate.ain.net`; `.blomp.com` is the documented one).
- **Works, verified live with 201 Created:**
  1. **Keystone v3** — `POST /v3/auth/tokens`
     body: `{"auth":{"identity":{"methods":["password"],"password":{"user":
     {"name":"<email>","domain":{"name":"Default"},"password":"<pass>"}}}}}`
     Token comes back in the `X-Subject-Token` header. The Swift storage URL
     comes from the token **body** → `token.catalog[]` → entry with
     `"type": "object-store"` → the `public` endpoint.
  2. **Keystone v2.0** — `POST /v2.0/tokens`
     body: `{"auth":{"passwordCredentials":{"username":"<email>",
     "password":"<pass>"},"tenantName":"storage"}}`
     Token from `access.token.id`, URL from `access.serviceCatalog`
     (object-store → publicURL). This is what rclone users configure
     (`auth_version=2`, `tenant=storage`) — proven working in July 2026.
  3. **Plain HTTP auth also works** (`http://authenticate.blomp.com/v3/auth/tokens`
     → 201). Blomp's own storage API is http-only anyway (see §4), so
     TinyVault tries http first, then https, for v3 then v2.
- **Does NOT work (do not try again):**
  - v1 tempauth (`/auth/v1.0` with X-Auth-User/X-Auth-Key) → 404 on both hosts.
  - Any auth at `authenticate.ain.net` with v1 headers → 404.
- Tokens live ~1 hour; re-auth on 401. Note: bursting re-auths during their
  limiter window can 401 for a while even with fresh tokens — back off 15–45s.

## 3. The network layer (this caused most of the pain)

The auth + storage hostnames resolve to **three IPs**:
`205.134.187.66`, `.67`, `.68`.

- **.66 is dead** — refuses connections (curl: HTTP 000). .67/.68 are healthy.
- Java's HttpClient dials **only the first resolved IP** and dies
  (`ConnectException`, `HTTP connect timed out`) — curl/Go/Python fail over
  automatically, Java does not.
- This single fact explains ~90% of the "HTTP connect timed out" /
  "network hiccup" errors seen all day.
- **TinyVault's fix:** `connectableUri()` in `BlompSwiftStore` probes each
  IPv4 with a 3-second TCP connect, pins the first reachable one per hostname,
  and drops the pin on any failure so the next request re-resolves.
- IPv6 note: Java may hang trying IPv6 paths; set `-Djava.net.preferIPv4Stack=true`
  (the live test harness runs with it) or accept slightly slower failover.
- TLS gotcha: Blomp's certificate does NOT cover the bare IPs — swapping the
  host for an IP under **https** fails with "No subject alternative names
  matching IP address". That's why auth/storage go over **plain http first**
  (which their own storage layer forces anyway — see §4).

## 4. Swift behavior quirks (all verified live)

- **Storage endpoint:** `http://swiftproxy.acs.ai.net:8080/v1/AUTH_<project-id>`
  where the project id comes from the token. Both v2 and v3 tokens work there
  once ISSUE'd correctly.
- **Account-level listing (GET /v1/AUTH_xxx) returns 403 — by design,
  not an outage.** It would list every customer. Never build logic that needs
  it. (This tormented us for hours: "403 Forbidden" ≠ broken token!)
- **Account HEAD works** (X-Account-Bytes-Used). No quota header is served —
  TinyVault reports the total of its own container instead.
- **Containers: an account may only use the container named after its own
  email.** PUT/GET a container named anything else (`tinyvault-test`,
  `Minecraft`, …) → 403 at the proxy. TinyVault therefore resolves the
  container to the account email automatically (config `dir: "auto"`).
- **Re-PUT of an existing container** → 403 (not 204). TinyVault GETs the
  container first and only creates when missing.
- **The @ in the email container MUST stay raw.** URL-encoding `@` → `%40`
  makes the proxy see a different, forbidden container → 403. TinyVault
  keeps `@` unencoded in container/object paths.
- **Container/object GET/PUT/DELETE work fine** with correct tokens.
- **Uploads sometimes answer `503` while the bytes actually landed** — later
  GET serves the content perfectly. Rule: never trust the status code,
  verify by **content** (ETag md5 + full-object read-back).
- ETag on single PUT = md5 of the stored object (Swift standard) — reliable
  when the content is real, but verify by reading for 503 cases.
- **Ranged GETs are unreliable on Blomp** (404 even on healthy objects in
  some tests) — TinyVault never uses Range requests; it reads from byte 0 and
  compares the first 4 KiB / full md5 instead.
- **Deleting is REAL — there is NO trash bin.** `DELETE` is permanent.
  (S3 DELETE → trash behavior belongs to FileLu, not here.)
- **Bursts trigger a limiter:** rapid deletes/lists can get `503`s and then a
  short account-level `401` denial window (even with fresh tokens). Keep a
  ~4s pace between deletes and 15s+ cool-downs on 401/5xx. Scheduled runs
  delete at most 1–2 objects, so this only matters during big cleanups.
- **Listing is eventually consistent:** a deleted object can still appear
  (and even GET 404/403 differently) for a short while after deletion.
- **Rename (FileLu-style "move") does not exist for S5-style access.** The
  FileLu web-API rename kills S3 access to the object entirely (tested there).

## 5. What does NOT exist on Blomp (save your time)

- No trash bin → no purge API. Nothing to clean; deletion is forever.
- No server-side CopyObject/Rename usable from Swift API access.
- No S3-compatible layer, no WebDAV/FTP (BlompSupport confirms — forum t=2615).
- No documented trash/empty endpoint (probed `trash/empty`,
  `file/delete_permanently`, `file/purge` → "Invalid operation"/404).

## 6. How TinyVault 1.0.4 uses all of this

- `BlompSwiftStore` (one file — the whole storage layer):
  - auth via v3 → v2.0, http → https, each through `connectableUri()`;
  - container always = account email (config `dir: "auto"` handles this);
    custom prefixes are just extra path segments;
  - uploads: single PUT, then verify by ETag md5, then read-back md5 —
    corrupt uploads are detected and retried once before failing;
  - retention: after a verified upload, managed objects beyond `limit`
    (default 3) are deleted oldest-first, 4s apart;
  - everything that can fail transiently is retried with backoff
    (connect timeouts, dropped connections, 5xx, 401 cool-downs).
- Filename rule (universal, no separate format option):
  `backup-<timestamp>-<epoch-millis>.zip` using `timezone` + `time-format`:
  - `time-format: "24"` → `backup-2026-10-16_18.44-…zip`
  - `time-format: "12"` → `backup-2026-10-16_06.44-PM-…zip`
  The millis suffix is for unambiguous ordering (limit rotation sorts on it).
- Other pieces unchanged from the good old days: timer (`15m`/`4h`/`24`),
  extreme mode (shut-down backup with a 30-min session gate), Discord webhook
  embeds (start blue / success green / failure red), `/vault` commands
  (detail · back · list · fails · passes · storage · files [page]).
- Error messages classify 403 (outage/permission), timeouts (their API),
  checksum mismatch (retried), credentials — never guess "network hiccup"
  when it's actually Blomp.

## 7. Fast troubleshooting recipes

| Symptom | Meaning | Check |
| --- | --- | --- |
| 403 on account GET + all console OK | by design on Blomp | nothing — proceed |
| 403 on your own container/object ops | Blomp outage or non-email container | run `python3 test.py` |
| HTTP connect timed out repeatedly | dead node or Blomp API down | the store auto-fails-over; if persistent, Blomp is down |
| 503 on upload | Swift busy — bytes often still land | plugin auto-retries + verifies content |
| 401 mid-session | limiter cool-off or token expiry | auto-backoff 15–45s |
| DELETE leaves ghost in listing | eventual consistency | wait, re-list; GET will 404 |

Stability check: `python3 test.py [rounds]` — runs the full cycle (auth →
container → small + 48 MiB upload → checksum verify → download compare →
cleanup), reports ✅/⚠️/❌ per step, exit codes: 0 good, 1 fail, 2 outage.

## 8. Fighter's log (dead ends that cost the most time — don't repeat)

1. Token from v3 works on storage only when used with... everything. The 403s
   we blamed on "token privileges" were actually ① account listing (by
   design), ② foreign container names (forbidden), ③ `%40`-encoded container.
2. FileLu S5 remains the cautionary tale: overwrites and delete+recreates
   silently corrupt objects there. Blomp's Swift overwrites are clean —
   and uploads there are create-once anyway under new names, so this class
   of bug can't happen.
3. The `CertificateException: No subject alternative names` on auth = we
   pinned the IP under https. Use http auth first; https works only with
   the real hostname and needs DNS failover luck.
4. User-Agent is irrelevant (curl/rclone/Java/browser all accepted).
5. If a test shows full-pass 0 / outages N, read the steps top-down — the
   first ⚠️/❌ line tells which layer is failing; layers are:
   connectivity → auth → storage-proxy-accepts-token → container → upload →
   verify → download → cleanup.

## 9. Files

- `src/main/java/dev/tinyvault/BlompSwiftStore.java` — storage layer
- `src/main/java/dev/tinyvault/BackupCoordinator.java` — scheduling + runs
- `src/main/resources/config.yml` — the simple config (username, password,
  dir, limit, timer, timezone, time-format, webhook-url, extreme, extreme-min)
- `test.py` — stability tester (no credentials inside; reads .env)
- `archive/` — the full 1.0.3 FileLu generation (jar + source), kept as
  reference since Blomp was the chosen platform.

Last verified: 2026-10-16, uploads + rotation + cleanup all green against the
live account.

## 10.1 TLS-vs-IP pinning bug (found 2026-10-28)

The reachable-IP pin originally applied to BOTH http and https auth attempts.
Blomp's TLS certificate does not list their bare IPs, so any https attempt with
a pinned IP died with `certificate_unknown: No subject alternative names
matching IP address ...` (and when the pinned IP also went dead, all four auth
paths failed and the run aborted). Fix: pinning is now http-only; https keeps
the real hostname (TLS verifies it) and relies on the retry chain. Auth
failures also drop the pin so the next attempt re-probes DNS, and the failure
detail now lists the reason of EVERY auth path instead of a single cryptic
"null".

## 10.2 Safer autosave window (same day)

Autosave used to stay disabled for the ENTIRE run (save + archive + upload).
Since the finished ZIP cannot be affected by later world changes, autosave is
now restored immediately after the archive is written — the unprotected window
is only save+archive, never the upload. Paper also saves worlds
asynchronously, so a ~2.5s settle delay was added between save and archiving
so the ZIP never captures half-written region files.

## 10. CONFIRMED WORKING IN PRODUCTION — 2026-10-16 evening

The first real scheduled backup on the live server **completed successfully**
(Discord webhook reported "backup completed"), after all fixes above. Since
then, three network-level truths held in production too:

- The dead-IP pinning + failover survived real server starts (logs that used
  to show `HTTP connect timed out` now resolve automatically).
- Tokens from http auth are accepted by the storage proxy without issue.
- If you ever see `authenticate.blomp.com/` **with a trailing slash** in a
  failure detail, the deployed jar is older than the 2026-10-16 21:11+ build —
  swap in the fresh `TinyVault-1.0.4.jar`.
- One-off auth failures right after a restart were Blomp limiter cool-offs —
  wait for the next scheduled run; do not restart-spam.

Deployment remainder: `build/libs/TinyVault-1.0.4.jar` into `plugins/`, fill
`username`/`password` (quotes or spaces are tolerated), restart once.

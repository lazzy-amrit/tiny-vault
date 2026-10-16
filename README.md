# TinyVault

# ⚠️ NOT MADE FOR NORMAL USE ⚠️

**It is not made to be used normally — it was built for personal work.**

**Kindly read the code once and compile it according to your own work.**
**The base is done for you in a good way.**

Minimal Paper plugin that backs up the entire server directory to FileLu S5
(S3-compatible) object storage and keeps exactly two remote ZIP archives:

- `backups/tv-<timestamp>.zip` — exactly the two newest are kept. The
  newest slot *is* the "current.zip" of this design (latest successful
  backup); the slot behind it is the "old.zip" (previous successful backup).

Backups run on a `timer` interval you set yourself — `24h` (default), `4h`,
or minutes like `15m` (`m` = minutes, `h` = hours; a plain unsuffixed number
stays hours for older configs) — or, with `extreme: true`, automatically
right before the server stops.

## Requirements

- Paper 26.2 (Minecraft's 2026 "26.2" release; the server runs on Java 25)
- A FileLu folder enabled as an **S5 Bucket**, and S5 credentials from your
  FileLu account (My Account -> S5 Access Key / Secret Key)

## Build

```bash
gradle shadowJar
```

A Java 25 toolchain is provisioned automatically by Gradle. Output:
`build/libs/TinyVault-1.0.1.jar`.

## Install

1. Copy the jar into the server's `plugins/` folder and start the server once.
2. Fill in `plugins/TinyVault/config.yml`:
   - `bucket` — the FileLu folder you enabled as an S5 Bucket
     (file manager -> Edit Folder -> S5 Bucket -> Save)
   - `access-key` / `secret-key` — S5 credentials generated under S5 settings in
     My Account on filelu.com (not the WebDAV/FTP passwords)
   - `endpoint` — defaults to `https://ap.s5lu.com` (Asia Pacific); regional
     alternatives are `https://us.s5lu.com`, `https://eu.s5lu.com`,
     `https://me.s5lu.com` (the global host from FileLu docs is
     `https://s5lu.com`)
   - `region` — FileLu's documented default is `global`; endpoint-specific
     values are `us-east`, `eu-central`, `ap-southeast`, `me-central`
   - `timer` — backup interval with unit suffix: `"15m"` = every 15
     **m**inutes, `"4h"` = every 4 **h**ours (plain `24` = 24 hours, for
     older configs). Nothing goes beyond hours: there is no `d` for days —
     days are expressed in hours.
   - `webhook-url` — optional Discord webhook URL (see the Webhooks section
     below). Empty = no Discord announcements.
   - `extreme` — `true` forgets the timer entirely: a backup runs
     automatically right before the server stops, and a manual backup in
     flight is always waited for. Sessions shorter than
     `extreme-min-session-minutes` (default 30) are skipped, so quick
     start/stop testing rounds never trigger backups; a one-time startup
     backup still happens if the bucket never received a successful backup.
   - `timezone` — IANA zone for command output (default `Asia/Kolkata`),
     e.g. `Asia/Dhaka`, `Europe/Berlin`, `UTC`
   Keep this file private — it contains credentials.
3. Restart the server.

## Discord webhooks

TinyVault can announce every backup on a Discord channel. In Discord:
**Server Settings → Integrations → Webhooks → New Webhook → (pick a channel) →
Copy Webhook URL**, and paste that URL into `webhook-url` in the plugin's
`config.yml`. Announcements fire at three moments, as colored embeds:

- **Backup started** (blue) — the trigger (timer schedule, `/vault back`,
  server shutdown…), plus a note that the whole server is being saved and
  autosaves are paused meanwhile.
- **Backup completed** (green) — archive size, how long it took, and when
  the next one is due.
- **Backup failed** (red) — a plain-language explanation of what happened,
  plus the raw technical error for digging.

Notes:

- Announcements are optional — leave `webhook-url` empty and nothing is sent.
- Posting happens on its own background thread: Discord being slow or down
  never delays a backup and never blocks server shutdown.
- A failed announcement only appears as a one-line warning in the server log;
  it never counts as a backup failure.
- The webhook never leaks your storage credentials — messages only contain
  sizes, durations, triggers and failure reasons.

## Behavior

- On startup the plugin reads `plugins/TinyVault/state.txt`. If the `timer`
  deadline of the last successful backup is already overdue, a backup starts
  immediately; otherwise it runs when the deadline passes (checked every five
  minutes, so it never backs up early and catches up right after the deadline).
  With `extreme: true` the schedule is skipped completely and backups happen
  at server shutdown instead (see the config section above).
- New uploads are verified by content, not just by HEAD metadata: FileLu's
  gateway can keep serving the metadata of the *previous* object for minutes
  after an overwrite, which used to cause false "expected N bytes" failures.
  Verification now recognizes that stale state, waits with growing backoff
  (over twenty minutes total), and reads the tail of the stored object
  directly with a ranged GET — actual object bytes prove the upload landed,
  so the wait usually ends after one check instead of failing the run.
- Each run: all worlds and player data are saved on the main thread (autosave
  is paused while the archive is written and restored afterwards), then the
  whole server directory — excluding TinyVault's own folder, which holds the
  state file, config and temp archives — is streamed into one ZIP.
- Two slots are kept, but on FileLu they are **not named `current.zip` /
  `old.zip`** — and that is deliberate. Verified live against FileLu's real
  S5 gateway: overwriting an existing key breaks the object (HEAD and the
  folder listing keep showing it, but the next GET returns 404), and so does
  re-creating a key after a delete. Create-once uploads are the only form
  that stayed reliable, so every backup lands on its own key
  (`backups/tv-<millis>.zip`) and the two newest are kept:
  1. The new archive uploads under a fresh key and is verified by content
     (ranged tail read; multipart PUTs that fail abort and abort cleanly).
  2. Only when two verified slots exist, older overflow and any leftover
     legacy `current.zip`/`old.zip` from older plugin versions are deleted,
     and their FileLu trash entries are purged (needs `api-key`).
  So the bucket always holds exactly two healthy, downloadable zips: the
  newest (your "current") and the one behind it (your "old").
  There is no remote temp object, nothing lingers in the bucket or trash.
  Standard S3 (CopyObject-capable providers) still uses the classic fixed
  `current.zip`/`old.zip` names.
- Archives larger than 64 MiB are uploaded as S3 multipart (64 MiB parts, up
  to three in parallel) because FileLu's gateway rejects long single-request
  PUTs. Interrupted uploads are aborted immediately, and any leftover
  multipart uploads are aborted at the start of the next run so partial parts
  never consume quota. For multipart objects the checksum check falls back to
  size-only (multipart ETags are not MD5).
- FileLu S5's gateway does not support server-side CopyObject (returns 500),
  so the previous backup is preserved by downloading it locally during the
  run and re-uploading it to `last.zip`. Only PUT / HEAD / GET / DELETE are
  used, with path-style addressing.
- `state.txt` keeps the last 5 successful timestamps, the last 10 failures
  with brief reasons, and the current status under `#Timestamps`, `#failures`
  and `#current`. It is updated atomically (temp file + move) and only marked
  successful after the new remote backup is confirmed.
- All archive and upload work runs off the main thread. Temporary archives
  live in `plugins/TinyVault/tmp/` and are deleted after every attempt.

## Storage quota and FileLu trash warning

FileLu S5 quota is shared per bucket/folder (1 GB by default on typical plans).
At any moment during rotation at most two archives exist remotely:
2 x ~370 MB = ~740 MB of the 1 GB quota. If your archive grows beyond roughly
half the free quota, the rotation will fail with a quota error; TinyVault then
reports the failure, keeps the surviving backups, and retries at the next
deadline — it never deletes the only valid backup to make room.

**FileLu trash:** every S3 DELETE lands in FileLu's trash (deleted backups
are re-sent from there if you need recovery). If FileLu counts trash contents
against your quota, empty the trash from the FileLu web UI periodically — the
S3 API cannot purge it. TinyVault deletes are conditional (existing objects
only) and never create random leftover objects, so trash entries come only
from intentional rotations (one per run: the oldest backup).

## Limits

- Single-request uploads are capped at 5 GiB by the S3 API; archives must stay
  below that.
- The FileLu folder must be enabled as an S5 bucket first; TinyVault does not
  create buckets.


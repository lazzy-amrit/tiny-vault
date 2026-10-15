# TinyVault

Minimal Paper plugin that backs up the entire server directory to FileLu S5
(S3-compatible) object storage and keeps exactly two remote ZIP archives:

- `backups/current.zip` — latest successful backup
- `backups/last.zip` — previous successful backup

Backups run every `timer` hours (by default 24).

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
   - `timer` — backup interval, read as hours (default 24)
   - `timezone` — IANA zone for command output (default `Asia/Kolkata`),
     e.g. `Asia/Dhaka`, `Europe/Berlin`, `UTC`
   Keep this file private — it contains credentials.
3. Restart the server.

## Behavior

- On startup the plugin reads `plugins/TinyVault/state.txt`. If the `timer`
  deadline of the last successful backup is already overdue, a backup starts
  immediately; otherwise it runs when the deadline passes (checked every five
  minutes, so it never backs up early and catches up right after the deadline).
- Each run: all worlds and player data are saved on the main thread (autosave
  is paused while the archive is written and restored afterwards), then the
  whole server directory — excluding TinyVault's own folder, which holds the
  state file, config and temp archives — is streamed into one ZIP.
- Two-slot rotation per run:
  1. `backups/last.zip` (the oldest) is deleted first to make room.
  2. If `backups/current.zip` exists, it is downloaded to a local temp file.
  3. The new ZIP is uploaded straight to `backups/current.zip` and verified
     (multipart PUTs are atomic: a failed upload leaves the old object intact).
  4. The downloaded copy is uploaded to `backups/last.zip` and verified.
  There is no remote temp object — nothing like `uploading.zip` is created, so
  nothing lingers in your FileLu bucket or trash between runs (old leftovers
  from previous plugin versions are cleaned up automatically at the next run).
  A failed run leaves any surviving backup untouched.
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


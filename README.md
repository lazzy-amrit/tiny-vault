# TinyVault

Minimal Paper plugin that backs up the entire server directory to FileLu S5
(S3-compatible) object storage every 24 hours and keeps exactly one remote
backup: `backups/current.zip`.

## Requirements

- Paper 26.2 (Minecraft's 2026 "26.2" release; the server runs on Java 25)
- A FileLu folder enabled as an **S5 Bucket**, and S5 credentials from your
  FileLu account (My Account -> S5 Access Key / Secret Key)

## Build

```bash
gradle shadowJar
```

A Java 25 toolchain is provisioned automatically by Gradle. Output:
`build/libs/TinyVault-1.jar`.

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
   Keep this file private — it contains credentials.
3. Restart the server.

## Behavior

- On startup the plugin reads `plugins/TinyVault/state.txt`. If the 24-hour
  deadline of the last successful backup is already overdue, a backup starts
  immediately; otherwise it runs when the deadline passes (checked every five
  minutes, so it never backs up early and catches up right after the deadline).
- Each run: all worlds and player data are saved on the main thread (autosave
  is paused while the archive is written and restored afterwards), then the
  whole server directory — excluding TinyVault's own folder, which holds the
  state file, config and temp archive — is streamed into one ZIP.
- Upload policy (one remote backup only):
  1. The ZIP is uploaded to `backups/uploading.zip` and verified (size and
     checksum).
  2. Only after the temp upload is confirmed is the old `backups/current.zip`
     deleted, and the confirmed archive is uploaded to
     `backups/current.zip` and verified again.
  3. The temp object is removed. A failed or interrupted upload leaves the
     existing `current.zip` untouched.
- Archives larger than 64 MiB are uploaded as S3 multipart (64 MiB parts, up
  to three in parallel) because FileLu's gateway rejects long single-request
  PUTs. Interrupted uploads are aborted immediately, and any leftover
  multipart uploads are aborted at the start of the next run so partial parts
  never consume quota. For multipart objects the checksum check falls back to
  size-only (multipart ETags are not MD5).
- FileLu S5's documented feature set does not include server-side CopyObject,
  so promotion is a second upload of the same local file rather than a copy or
  rename. Only the universally supported PUT / HEAD / DELETE operations are
  used, with path-style addressing.
- `state.txt` keeps the last 5 successful timestamps, the last 10 failures
  with brief reasons, and the current status under `#Timestamps`, `#failures`
  and `#current`. It is updated atomically (temp file + move) and only marked
  successful after the new remote backup is confirmed.
- All archive and upload work runs off the main thread. Temporary archives
  live in `plugins/TinyVault/tmp/` and are deleted after every attempt.

## Storage quota trade-off

FileLu S5 quota is shared per bucket/folder (1 GB by default on typical plans)
and the S3 API has no atomic rename, so while a new archive uploads, the old
`current.zip` and the `backups/uploading.zip` temp object coexist
simultaneously. With the current ~350 MB archive that is ~700 MB of the 1 GB
quota, which fits. If your archive grows beyond roughly half the free quota,
the temp upload will fail with a quota error; TinyVault then keeps the existing
`current.zip` intact, records the failure in `state.txt`, and logs it. It never
deletes the only valid backup to make room for an unverified replacement — if
you hit this, raise the quota or reduce the server data size.

## Limits

- Single-request uploads are capped at 5 GiB by the S3 API; archives must stay
  below that.
- The FileLu folder must be enabled as an S5 bucket first; TinyVault does not
  create buckets.

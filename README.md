# TinyVault 1.0.4

TinyVault is a Paper plugin that creates one ZIP of the server directory and stores it in Blomp using the OpenStack Swift-compatible API. Backups are scheduled, can be started manually, and retain a configurable number of TinyVault-managed archives.

## Requirements

- Paper 26.2 or compatible server, running Java 25.
- A Blomp account with Swift API access enabled.
- Gradle and a Java 25 toolchain to build.

## Build and install

Run:

```sh
gradle clean test shadowJar
```

The distributable is `build/libs/TinyVault-1.0.4.jar`. Install it in the server's `plugins/` directory and start the server once to create `plugins/TinyVault/config.yml`. Stop the server, enter Blomp credentials, then restart.

The generated configuration contains blank credential fields by design. Do not commit or share a populated config file.

## Blomp connection and destination

TinyVault uses Blomp's Swift-compatible authentication at `https://authenticate.blomp.com` —
Keystone **v3** first (your email as the user, password method, `Default` domain) with a
documented **v2.0** (`tenantName: storage`) fallback. The token and the storage URL come from
the token's service catalog; the plugin caches both and re-authenticates on 401. Credentials
and tokens are never logged.

The `dir` setting is a Swift container plus optional object prefix — the default `tinyvault`
keeps every ZIP in one clean container. For example:

```yaml
username: "your-blomp-account@example.com"
password: "your-blomp-password"
dir: "tinyvault"
limit: 3
timer: "24h"
```

The container is created on the first backup if it does not exist. The server directory itself
remains the backup source, matching prior TinyVault behavior.

## Backup names, limit and no-trash deletion

Names follow one universal rule with no separate format option: `backup-<date time>-<millis>.zip`
stamped in the configured `timezone`, styled by `time-format` — `24` produces names like
`backup-2026-10-16_18.44-…zip`, `12` puts AM/PM into the name (`backup-2026-10-16_06.44-PM-…zip`).

`limit` (default 3) is how many managed backups may exist at peak. After a new backup uploads
and verifies, the oldest ones beyond the limit are removed for real — Swift has no trash bin.
Unrelated files in the container are never touched. Verify happens through Swift's ETag
checksum: if an upload arrives corrupted it is deleted immediately and the next run retries.

## Backups and retention

Each run saves worlds and player data, briefly pauses world autosaving while it writes the
archive, then streams the server tree into one ZIP. TinyVault's own plugin data directory is
excluded, so config, state, and temporary ZIPs cannot be included recursively. The ZIP is
uploaded as one Swift object (Swift's own 5 GiB limit covers it) and verified through the
returned ETag (md5).

**Known Blomp-side caveat:** as of building this version, Blomp's storage proxy
(`swiftproxy…:8080`) returns HTTP 403 to every valid token for some accounts/periods — an
outage documented since March 2026 on their own forum. TinyVault's commands will say
"Blomp's storage proxy refused the API call" when that happens; the credentials never leak
into such messages and the plugin retries on the next scheduled run automatically.

Object names follow one universal rule — `time-format` only, no separate filename pattern:

```text
backup-<date-time-stamp-in-timezone>-<epoch-milliseconds>.zip
```

24-hour mode gives `backup-2026-10-16_18.44-…zip`; 12-hour mode puts AM/PM into the name.
The epoch suffix prevents collisions between backups that share a formatted stamp.

After a successful upload (verified by ETag checksum), TinyVault lists the container/prefix
and removes only its own `backup-*.zip` objects beyond `limit`, oldest first. It never deletes
unrelated objects. A failed upload does not start retention cleanup, preserving prior valid
backups. If cleanup itself fails after upload, the new backup remains and a warning is logged;
cleanup can be retried on a later run.

Swift object uploads are single PUT requests. Standard Swift deployments commonly cap a single
object at 5 GiB; check your Blomp account/API limits for your plan. Archives exceeding provider
limits fail without intentionally deleting existing backups.

## Configuration

- `timer`: backup interval such as `15m`, `4h`, or `24h`. A plain number remains hours. Minimum is one minute.
- `timezone`: IANA zone used for filenames and command output, for example `Asia/Kolkata`, `Europe/Berlin`, or `UTC`.
- `time-format`: `24` for 24-hour names like `backup-2026-10-16_18.44-…`, `12` for names that carry AM/PM.
- `webhook-url`: optional Discord webhook for start, success, and failure messages. Leave blank to disable. Webhook delivery runs asynchronously and never blocks a backup.
- `extreme`: when `true`, skips periodic backups and backs up before server shutdown instead. A first backup still starts at startup when no success has been recorded.
- `extreme-min-session-minutes`: minimum server session length before an extreme-mode shutdown backup, default `30`.
- `limit`: number of managed backups to retain, default `3`.

## Commands

`/vault` is the existing command family; existing subcommands remain available:

- `/vault detail` shows status and recent remote backup objects. It remains available to all players.
- `/vault back` starts a manual backup.
- `/vault list` displays recorded successful backup times.
- `/vault fails` displays recent failures.
- `/vault passes` displays successful backup history.
- `/vault storage` refreshes Swift account metadata and shows used space, total quota, and available space when Blomp exposes those values.
- `/vault files [page]` lists visible containers, object paths, folder-like prefixes, and sizes, 20 entries per page.

All commands except `/vault detail` require operator status or the `tinyvault.admin` permission. Storage inspection is read-only. It does not offer bulk deletion or other destructive operations.

Swift account metadata commonly includes `X-Account-Bytes-Used`. A total quota is not guaranteed by the Swift API and may not be provided by Blomp; if unavailable, `/vault storage` says so rather than estimating or displaying a hardcoded plan size. The `available` field is only computed when both used bytes and a reported quota are present.

## Troubleshooting

- **Authentication fails:** check the Blomp account email/password, account status, and Swift access. TinyVault uses the Swift-compatible API credentials, not S3 keys.
- **Swift token or storage URL missing:** Blomp authentication did not return the expected Swift headers. Check current Blomp API availability and the configured network path.
- **Container or upload rejected:** check the `dir` container/prefix, account permissions, provider object-size limits, and available Blomp storage.
- **Quota shows unavailable:** the account's Swift endpoint did not return a quota metadata header. This is an API limitation, not a displayed estimate.
- **`/vault files` is incomplete or fails:** verify the credentials can list the account and inspect the server log for the HTTP status. The listing is restricted to the authenticated account's Swift-visible containers and objects.
- **Discord messages do not appear:** verify the webhook URL and channel permissions. Webhook failures are warnings only and do not fail a backup.
- **Backup schedule is unexpected:** check `timer`, `extreme`, and the latest status with `/vault detail`. Extreme mode disables the timer and uses the minimum-session gate at shutdown.

## Reference archive

`archive/` contains the previous 1.0.3 materials, including its source tree, build file, README, and JAR. That implementation used the older S3/FileLu-oriented storage code and includes the known bug-affected behavior; it is retained for reference only. The active implementation is under `src/` and targets Blomp Swift.

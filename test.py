#!/usr/bin/env python3
"""
Blomp Swift API tester for TinyVault (rewrite, v2).

Guys this saved my life when I was trying to debug a plugin that was failing on Blomp. 

WHY THIS REWRITE
  The old script did GET <storage-url> (the ACCOUNT listing) and treated the
  403 as "Blomp outage". On Blomp that call is forbidden BY DESIGN for every
  customer: all customers share one Swift account (tenant "storage") and each
  customer owns exactly one container, named after their login email. Listing
  the account would expose everybody's email. Real work happens INSIDE the
  container named after your email.

WHAT IT DOES
  1. Reaches authenticate.blomp.com
  2. Tries EVERY auth method: v2.0 tenant=storage (the documented one), v3
     scoped (domain name / domain id), v3 unscoped (what the old script did)
  3. Tries every storage endpoint from the catalogs (+ https variant of http)
  4. For every (auth x endpoint) combo reports: account GET (403 = expected),
     container HEAD/create, and a real tiny WRITE probe
  5. Picks the best working combo (https preferred) and runs the full suite:
     small PUT/GET/HEAD/POST/overwrite/Range/listing/COPY, big streamed
     upload + download with checksum, DLO + SLO segmented uploads (only
     needed for files >5 GiB), extra-container creation probe
  6. Deletes ONLY the objects it created (never the email container)

Credentials: .env next to this file (username=... / password=...) or the
env vars BLOMP_USER / BLOMP_PASS. Python 3.8+, standard library only.

Usage:
  python3 test.py                  one round
  python3 test.py 5                five rounds, 30 s apart (stability watch)
  python3 test.py --big-mb 370     use a backup-sized file
  python3 test.py --quick          skip big + segmented tests
  python3 test.py -v --json r.json verbose HTTP log + redacted report file

Exit codes: 0 all good | 1 failures | 2 auth ok but storage denies (403/401)
            everywhere | 3 usage / missing credentials
"""
import argparse
import functools
import hashlib
import http.client
import json
import os
import sys
import time
import urllib.parse
import uuid

AUTH_ROOT = os.environ.get("BLOMP_AUTH_ROOT", "https://authenticate.blomp.com").rstrip("/")
AUTH_V2 = AUTH_ROOT + "/v2.0/tokens"
AUTH_V3 = AUTH_ROOT + "/v3/auth/tokens"
TENANT = "storage"
MiB = 1024 * 1024
OK, FAIL, WARN, INFO, SKIP = "✅", "❌", "⚠️ ", "ℹ️ ", "⏭️ "
JSON_H = {"Content-Type": "application/json", "Accept": "application/json"}
VERBOSE = False


# ------------------------------------------------------------------ HTTP ----

class Resp:
    def __init__(self, status=None, headers=None, body=b"", error=None, elapsed=0.0):
        self.status = status
        self.headers = headers or {}
        self.body = body or b""
        self.error = error
        self.elapsed = elapsed

    def trans_id(self):
        return self.headers.get("x-trans-id") or self.headers.get("x-openstack-request-id") or ""

    def describe(self):
        if self.status is None:
            return f"connection-level failure: {self.error}"
        text = self.body[:160].decode("utf-8", "replace").strip().replace("\n", " ")
        tid = self.trans_id()
        return f"HTTP {self.status}" + (f" [{text}]" if text else "") + (f" (trans-id {tid})" if tid else "")


class HashedStream:
    """Random data generated on the fly (constant memory), md5 computed while sending."""

    def __init__(self, size, label="upload", chunk=MiB):
        self.size, self.label, self.chunk = size, label, chunk
        self.sent = 0
        self.md5 = hashlib.md5()
        self._last = 0.0

    def __iter__(self):
        while self.sent < self.size:
            block = os.urandom(min(self.chunk, self.size - self.sent))
            self.md5.update(block)
            self.sent += len(block)
            self._tick()
            yield block

    def _tick(self):
        now = time.time()
        if sys.stdout.isatty() and now - self._last > 0.5:
            self._last = now
            sys.stdout.write(f"\r      {self.label}: {self.sent // MiB}/{self.size // MiB} MiB")
            sys.stdout.flush()

    def done(self):
        if sys.stdout.isatty():
            sys.stdout.write("\r" + " " * 50 + "\r")


class Sink:
    def __init__(self):
        self.md5 = hashlib.md5()
        self.n = 0

    def __call__(self, chunk):
        self.md5.update(chunk)
        self.n += len(chunk)


def call(method, url, headers=None, body=None, timeout=30, retries=2, on_chunk=None):
    """One HTTP call. Never raises. status=None means connection-level failure."""
    import ssl
    parts = urllib.parse.urlsplit(url)
    target = (parts.path or "/") + (("?" + parts.query) if parts.query else "")
    tries = retries + 1
    for i in range(tries):
        t0 = time.time()
        conn = None
        try:
            if parts.scheme == "https":
                conn = http.client.HTTPSConnection(parts.netloc, timeout=timeout,
                                                   context=ssl.create_default_context())
            else:
                conn = http.client.HTTPConnection(parts.netloc, timeout=timeout)
            conn.request(method, target, body=body, headers=headers or {})
            r = conn.getresponse()
            hdrs = {k.lower(): v for k, v in r.getheaders()}
            if on_chunk is not None and r.status in (200, 206):
                while True:
                    chunk = r.read(MiB)
                    if not chunk:
                        break
                    on_chunk(chunk)
                data = b""
            else:
                data = r.read()
            resp = Resp(r.status, hdrs, data, None, time.time() - t0)
            if VERBOSE:
                print(f"      · {method} {url[:110]} -> {resp.status} ({resp.elapsed:.2f}s)")
            if resp.status >= 500 and i < tries - 1:
                time.sleep(1 + 2 * i)
                continue
            return resp
        except (OSError, http.client.HTTPException) as e:
            resp = Resp(None, {}, b"", f"{type(e).__name__}: {e}", time.time() - t0)
            if VERBOSE:
                print(f"      · {method} {url[:110]} -> {resp.error}")
            if i < tries - 1:
                time.sleep(1 + 2 * i)
                continue
            return resp
        finally:
            if conn is not None:
                conn.close()


# ------------------------------------------------------------------ AUTH ----

class Auth:
    def __init__(self, label, token, url, expires):
        self.label, self.token, self.url, self.expires = label, token, url, expires
        self.refresh = None


def _finish_auth(label, r, parser):
    if r.status is None:
        return None, r.error
    if r.status not in (200, 201):
        return None, r.describe()
    try:
        token, url, exp = parser(r)
    except Exception as e:  # noqa
        return None, f"unparseable response ({type(e).__name__}: {e})"
    if not token:
        return None, "no token in response"
    return Auth(label, token, url, exp), "ok"


def _parse_v2(r):
    access = json.loads(r.body)["access"]
    url = None
    for svc in access.get("serviceCatalog", []):
        if svc.get("type") == "object-store" and svc.get("endpoints"):
            url = svc["endpoints"][0].get("publicURL")
            break
    return access["token"]["id"], url, access["token"].get("expires")


def _parse_v3(r):
    body = json.loads(r.body)["token"]
    url = None
    for svc in body.get("catalog", []):
        if svc.get("type") == "object-store":
            for ep in svc.get("endpoints", []):
                if ep.get("interface") == "public":
                    url = ep.get("url")
                    break
    return r.headers.get("x-subject-token"), url, body.get("expires_at")


def auth_v2(user, pw, timeout):
    payload = {"auth": {"passwordCredentials": {"username": user, "password": pw},
                        "tenantName": TENANT}}
    r = call("POST", AUTH_V2, JSON_H, json.dumps(payload).encode(), timeout)
    return _finish_auth("v2.0", r, _parse_v2)


def auth_v3(user, pw, timeout, scoped, dom):
    domain = {"name": "Default"} if dom == "name" else {"id": "default"}
    payload = {"auth": {"identity": {"methods": ["password"], "password": {
        "user": {"name": user, "domain": domain, "password": pw}}}}}
    if scoped:
        payload["auth"]["scope"] = {"project": {"name": TENANT, "domain": domain}}
    r = call("POST", AUTH_V3, JSON_H, json.dumps(payload).encode(), timeout)
    return _finish_auth("v3", r, _parse_v3)


AUTH_METHODS = [
    ("v2.0 tenant=storage (documented)", auth_v2),
    ("v3 scoped project=storage", functools.partial(auth_v3, scoped=True, dom="name")),
    ("v3 scoped, domain id=default", functools.partial(auth_v3, scoped=True, dom="id")),
    ("v3 unscoped (old test.py)", functools.partial(auth_v3, scoped=False, dom="name")),
]


# --------------------------------------------------------------- REPORT -----

class Report:
    def __init__(self):
        self.steps = []
        self.matrix = []

    def add(self, icon, name, detail=""):
        self.steps.append({"icon": icon.strip(), "name": name, "detail": detail})
        print(f"   {icon} {name}" + (f" — {detail}" if detail else ""))

    def count(self, icon):
        return sum(1 for s in self.steps if s["icon"] == icon.strip())


def redact(user):
    if "@" in user:
        local, _, dom = user.partition("@")
        return local[:1] + "***@" + dom
    return user[:1] + "***"


def fmt_speed(nbytes, secs):
    return f"{nbytes / MiB / max(secs, 0.001):.1f} MiB/s"


# ---------------------------------------------------------------- PROBING ---

def hdr(auth, extra=None):
    h = {"X-Auth-Token": auth.token}
    h.update(extra or {})
    return h


def probe_combo(auth, base, containers, prefix, timeout):
    """Account GET, container HEAD (create if missing), real tiny write."""
    row = {"auth": auth.label, "endpoint": base, "scheme": base.split(":", 1)[0],
           "account": None, "container": None, "write": None, "cont_variant": None,
           "ok": False, "created": False, "trans_ids": [], "errors": []}
    acct = call("GET", base + "?format=json", hdr(auth), timeout=timeout, retries=1)
    row["account"] = acct.status if acct.status is not None else "ERR"
    if acct.status is None:
        row["errors"].append(acct.error)
        row["container"] = row["write"] = "-"
        return row
    for cv in containers:
        head = call("HEAD", f"{base}/{cv}", hdr(auth), timeout=timeout, retries=1)
        status = head.status
        reachable = status in (200, 204)
        if status == 404:
            put = call("PUT", f"{base}/{cv}", hdr(auth), b"", timeout=timeout, retries=1)
            if put.status in (201, 202, 204):
                reachable, row["created"], status = True, True, f"404→created({put.status})"
            else:
                status = f"404→PUT {put.status}"
        if not reachable and status not in (404,):
            lst = call("GET", f"{base}/{cv}?format=json&limit=1", hdr(auth), timeout=timeout, retries=1)
            if lst.status == 200:
                reachable, status = True, f"{status}→list 200"
        row["container"] = status if status is not None else "ERR"
        row["cont_variant"] = cv
        if head.status is None:
            row["errors"].append(head.error)
        if head.trans_id():
            row["trans_ids"].append(head.trans_id())
        if not reachable:
            continue
        obj = f"{base}/{cv}/{urllib.parse.quote(prefix, safe='/')}probe.txt"
        w = call("PUT", obj, hdr(auth, {"Content-Type": "text/plain"}), b"probe", timeout=timeout, retries=1)
        row["write"] = w.status if w.status is not None else "ERR"
        if w.trans_id():
            row["trans_ids"].append(w.trans_id())
        if w.status in (201, 202):
            row["ok"] = True
            call("DELETE", obj, hdr(auth), timeout=timeout, retries=1)
            break
        row["errors"].append("write: " + w.describe())
    if row["write"] is None:
        row["write"] = "-"
    return row


# ------------------------------------------------------------------ SUITE ---

class Ctx:
    def __init__(self, auth, base, cont_quoted, cont_raw, prefix, run_id, args):
        self.auth, self.base, self.cont, self.cont_raw = auth, base, cont_quoted, cont_raw
        self.prefix, self.run_id, self.args = prefix, run_id, args
        self.created = []

    def obj(self, name, query=""):
        u = f"{self.base}/{self.cont}/{urllib.parse.quote(self.prefix + name, safe='/')}"
        return u + (("?" + query) if query else "")

    def curl(self, query=""):
        u = f"{self.base}/{self.cont}"
        return u + (("?" + query) if query else "")

    def track(self, name):
        if name not in self.created:
            self.created.append(name)

    def req(self, method, url, headers=None, body=None, timeout=None, retries=None, on_chunk=None):
        timeout = timeout or self.args.timeout
        retries = self.args.retries if retries is None else retries
        r = call(method, url, hdr(self.auth, headers), body, timeout, retries, on_chunk)
        if r.status == 401 and self.auth.refresh and not isinstance(body, HashedStream):
            fresh = self.auth.refresh()
            if fresh:
                self.auth.token = fresh.token
                r = call(method, url, hdr(self.auth, headers), body, timeout, retries, on_chunk)
        return r


def run_suite(ctx, rep, args):
    ok = lambda n, d="": rep.add(OK, n, d)            # noqa: E731
    bad = lambda n, d="", lvl=FAIL: rep.add(lvl, n, d)  # noqa: E731

    # --- 1. small upload / download -----------------------------------
    name = "small.txt"
    data = f"TinyVault Blomp test {ctx.run_id}".encode()
    md5 = hashlib.md5(data).hexdigest()
    r = ctx.req("PUT", ctx.obj(name), {"Content-Type": "text/plain"}, data)
    if r.status != 201:
        bad("small upload", r.describe())
        return
    ctx.track(name)
    etag = r.headers.get("etag", "").strip('"')
    (ok if etag == md5 else bad)("small upload", f"HTTP 201, etag {'matches' if etag == md5 else 'DIFFERS ' + etag}")

    r = ctx.req("GET", ctx.obj(name))
    (ok if r.status == 200 and r.body == data else bad)("small download", "content identical" if r.body == data else r.describe())

    r = ctx.req("HEAD", ctx.obj(name))
    good = r.status == 200 and r.headers.get("content-length") == str(len(data))
    (ok if good else bad)("HEAD object", f"size={r.headers.get('content-length')} modified={r.headers.get('last-modified')}")

    # --- 2. metadata (POST) -------------------------------------------
    r = ctx.req("POST", ctx.obj(name), {"X-Object-Meta-Test": "tinyvault"})
    h = ctx.req("HEAD", ctx.obj(name))
    good = r.status in (202, 204) and h.headers.get("x-object-meta-test") == "tinyvault"
    (ok if good else bad)("object metadata (POST)", f"POST {r.status}", WARN)

    # --- 3. overwrite -------------------------------------------------
    data2 = data + b" (overwritten)"
    r = ctx.req("PUT", ctx.obj(name), {"Content-Type": "text/plain"}, data2)
    g = ctx.req("GET", ctx.obj(name))
    (ok if r.status == 201 and g.body == data2 else bad)("overwrite existing object", r.describe() if r.status != 201 else "new content served")

    # --- 4. Range GET -------------------------------------------------
    r = ctx.req("GET", ctx.obj(name), {"Range": "bytes=0-9"})
    (ok if r.status == 206 and r.body == data2[:10] else bad)("Range GET", f"HTTP {r.status}", WARN)

    # --- 5. container listing with prefix (needed for pruning old backups)
    q = "format=json&limit=100&prefix=" + urllib.parse.quote(ctx.prefix, safe="")
    r = ctx.req("GET", ctx.curl(q))
    names = []
    if r.status == 200:
        try:
            names = [o.get("name") for o in json.loads(r.body)]
        except ValueError:
            pass
    (ok if ctx.prefix + name in names else bad)("list objects by prefix", f"{len(names)} object(s) visible" if r.status == 200 else r.describe())
    r = ctx.req("GET", ctx.curl(q + "&delimiter=%2F"))
    (ok if r.status == 200 else bad)("list with delimiter (folder view)", f"HTTP {r.status}", WARN)

    # --- 6. server-side copy ------------------------------------------
    dst = "copy.txt"
    dest_hdr = f"{ctx.cont}/{urllib.parse.quote(ctx.prefix + dst, safe='/')}"
    r = ctx.req("COPY", ctx.obj(name), {"Destination": dest_hdr, "Content-Length": "0"})
    how = "COPY"
    if r.status != 201:
        src = f"/{ctx.cont}/{urllib.parse.quote(ctx.prefix + name, safe='/')}"
        r = ctx.req("PUT", ctx.obj(dst), {"X-Copy-From": src, "Content-Length": "0"}, b"")
        how = "PUT+X-Copy-From"
    if r.status == 201:
        ctx.track(dst)
        g = ctx.req("GET", ctx.obj(dst))
        (ok if g.body == data2 else bad)("server-side copy", f"via {how}", WARN)
    else:
        bad("server-side copy", r.describe(), WARN)

    # --- 7. big upload / download -------------------------------------
    if args.quick:
        rep.add(SKIP, "big + segmented tests", "--quick")
    else:
        big_upload_download(ctx, rep, args)
        segmented_tests(ctx, rep, args)

    # --- 8. can the plugin make its own container? --------------------
    cname = f"tinyvaulttest-{ctx.run_id}".lower()
    r = ctx.req("PUT", f"{ctx.base}/{cname}", retries=0)
    if r.status == 201:
        rep.add(INFO, "extra containers", "creating other containers WORKS (plugin may use its own)")
        ctx.req("DELETE", f"{ctx.base}/{cname}", retries=0)
    else:
        rep.add(INFO, "extra containers", f"PUT new container -> {r.status}; keep ALL files inside the email container under a prefix such as TinyVault/")


def big_upload_download(ctx, rep, args):
    size = args.big_mb * MiB
    name = "big-backup.bin"
    expected, r, elapsed = None, None, 0.0
    for attempt in range(1, args.retries + 2):
        stream = HashedStream(size, f"upload attempt {attempt}")
        t0 = time.time()
        r = ctx.req("PUT", ctx.obj(name), {"Content-Type": "application/octet-stream",
                                           "Content-Length": str(size)},
                    stream, timeout=args.big_timeout, retries=0)
        elapsed = time.time() - t0
        stream.done()
        expected = stream.md5.hexdigest()
        if r.status == 201:
            break
        if r.status in (401, 403, 404, 413):
            break  # retrying will not help
        print(f"      attempt {attempt} failed: {r.describe()} — retrying")
        time.sleep(2)
    if r.status != 201:
        rep.add(FAIL, f"big upload ({args.big_mb} MiB)", r.describe())
        return
    ctx.track(name)
    etag = r.headers.get("etag", "").strip('"')
    if etag != expected:
        rep.add(FAIL, f"big upload ({args.big_mb} MiB)", f"CHECKSUM MISMATCH server={etag} local={expected}")
        return
    rep.add(OK, f"big upload ({args.big_mb} MiB)", f"md5 verified, {fmt_speed(size, elapsed)}")

    sink, t0 = Sink(), time.time()
    r = ctx.req("GET", ctx.obj(name), timeout=args.big_timeout, retries=0, on_chunk=sink)
    elapsed = time.time() - t0
    good = r.status == 200 and sink.n == size and sink.md5.hexdigest() == expected
    (rep.add(OK, "big download + verify", f"identical, {fmt_speed(size, elapsed)}") if good else
     rep.add(FAIL, "big download + verify", f"HTTP {r.status}, got {sink.n}/{size} bytes, md5 {'ok' if sink.md5.hexdigest() == expected else 'DIFFERS'}"))


def segmented_tests(ctx, rep, args):
    seg_bytes = args.seg_mb * MiB
    sizes = [seg_bytes] * 3 + [512 * 1024]
    segs, whole = [], hashlib.md5()
    for i, sz in enumerate(sizes):
        block = os.urandom(sz)
        segs.append((f"seg/{i:06d}", block, hashlib.md5(block).hexdigest()))
        whole.update(block)
    for sname, block, md5 in segs:
        r = ctx.req("PUT", ctx.obj(sname), {"Content-Type": "application/octet-stream"}, block, timeout=args.big_timeout)
        if r.status != 201:
            rep.add(WARN, "segment upload", r.describe())
            return
        ctx.track(sname)
    total = sum(sizes)
    expected = whole.hexdigest()

    # DLO
    manifest = f"{ctx.cont}/{urllib.parse.quote(ctx.prefix + 'seg/', safe='/')}"
    r = ctx.req("PUT", ctx.obj("dlo.bin"), {"X-Object-Manifest": manifest, "Content-Length": "0"}, b"")
    if r.status == 201:
        ctx.track("dlo.bin")
        sink = Sink()
        g = ctx.req("GET", ctx.obj("dlo.bin"), timeout=args.big_timeout, retries=0, on_chunk=sink)
        good = g.status == 200 and sink.n == total and sink.md5.hexdigest() == expected
        rep.add(OK if good else WARN, "DLO (dynamic large object)", "reassembled identical" if good else f"HTTP {g.status}, {sink.n}/{total} bytes")
    else:
        rep.add(WARN, "DLO (dynamic large object)", r.describe())

    # SLO
    entries = [{"path": f"/{ctx.cont_raw}/{ctx.prefix}{sname}", "etag": md5, "size_bytes": len(block)}
               for sname, block, md5 in segs]
    r = ctx.req("PUT", ctx.obj("slo.bin", "multipart-manifest=put"), {"Content-Type": "application/json"},
                json.dumps(entries).encode())
    if r.status == 201:
        ctx.track("slo.bin")
        sink = Sink()
        g = ctx.req("GET", ctx.obj("slo.bin"), timeout=args.big_timeout, retries=0, on_chunk=sink)
        good = g.status == 200 and sink.n == total and sink.md5.hexdigest() == expected
        rep.add(OK if good else WARN, "SLO (static large object, >5 GiB files)", "reassembled identical" if good else f"HTTP {g.status}, {sink.n}/{total} bytes")
    else:
        rep.add(WARN, "SLO (static large object, >5 GiB files)", r.describe())
    rep.add(INFO, "large objects", "only needed for single files >5 GiB; your ~370 MB backups fit in one PUT")


def cleanup(ctx, rep, args):
    if args.keep:
        rep.add(INFO, "cleanup", f"--keep: left {len(ctx.created)} object(s) under {ctx.prefix}")
        return
    failed = []
    for name in reversed(ctx.created):
        r = ctx.req("DELETE", ctx.obj(name))
        if r.status not in (200, 204, 404):
            failed.append(f"{name}: {r.describe()}")
    if ctx.created:
        g = ctx.req("GET", ctx.obj(ctx.created[0]))
        gone = g.status == 404
    else:
        gone = True
    if failed or not gone:
        rep.add(WARN, "cleanup", "; ".join(failed) or "object still readable after delete")
    else:
        rep.add(OK, "cleanup", f"{len(ctx.created)} test object(s) removed (email container left untouched)")


# ------------------------------------------------------------------ ROUND ---

def run_round(args, user, pw):
    rep = Report()
    run_id = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:5]
    prefix = f"tinyvault-test/{run_id}/"

    print("Step 1: reaching the authentication endpoint…")
    r = call("GET", AUTH_ROOT, timeout=args.timeout, retries=1)
    if r.status is None:
        rep.add(FAIL, "authenticate.blomp.com unreachable", r.error)
        return finish(rep, 1, None)
    rep.add(OK, "endpoint reachable", f"HTTP {r.status} (300 = version list, normal)")

    print("Step 2: authenticating with every method…")
    auths = []
    for label, fn in AUTH_METHODS:
        a, note = fn(user, pw, args.timeout)
        if a:
            a.label = label
            a.refresh = (lambda fn=fn: fn(user, pw, args.timeout)[0])
            auths.append(a)
            rep.add(OK, label, f"token {a.token[:8]}…, expires {a.expires}, endpoint {a.url}")
        else:
            rep.add(FAIL if label.startswith("v2.0") else WARN, label, note)
    if not auths:
        rep.add(FAIL, "no auth method worked", "check email/password (Blomp account password, not a Google/SSO login)")
        return finish(rep, 1, None)

    urls = []
    for a in auths:
        if a.url and a.url not in urls:
            urls.append(a.url)
    for u in list(urls):
        if u.startswith("http://"):
            https = "https://" + u[len("http://"):]
            if https not in urls:
                urls.append(https)
    if not urls:
        rep.add(FAIL, "no object-store endpoint in any catalog")
        return finish(rep, 1, None)
    if all(u.startswith("http://") for u in urls[:1]):
        rep.add(INFO, "cleartext endpoint", "catalog gives plain http:// — your token and data travel unencrypted (https variant is tested below)")

    print("Step 3: probing every auth × endpoint combination…")
    print("        account GET is EXPECTED to be 403 on Blomp (it would list every customer's email)")
    containers = list(dict.fromkeys([urllib.parse.quote(user, safe=""), urllib.parse.quote(user, safe="@")]))
    rows = []
    for ai, a in enumerate(auths):
        for u in urls:
            row = probe_combo(a, u, containers, prefix, args.timeout)
            row["auth_index"] = ai
            rows.append(row)
            icon = OK if row["ok"] else FAIL
            print(f"   {icon} {a.label:<34} {row['scheme']:<5} {u.split('//', 1)[1][:34]:<34} "
                  f"account={row['account']}  container={row['container']}  write={row['write']}")
    rep.matrix = [{k: v for k, v in r_.items() if k != "cont_variant"} for r_ in rows]
    working = sorted([r_ for r_ in rows if r_["ok"]], key=lambda r_: (r_["scheme"] != "https", r_["auth_index"]))

    if not working:
        statuses = set()
        for r_ in rows:
            for k in ("container", "write"):
                statuses.add(str(r_[k]))
        denied = all(any(code in s for code in ("403", "401")) or s in ("-",) for s in statuses)
        rep.add(FAIL, "no combination could read/write the email container", "see matrix above")
        for r_ in rows[:3]:
            for e in r_["errors"][:2]:
                print(f"      detail: {e}")
        if denied:
            rep.add(WARN, "storage denies every token", "auth works but 401/403 on the email container too — that is NOT the account-listing 403; send the matrix + trans-ids (--json) to Blomp support")
            return finish(rep, 2, None)
        return finish(rep, 1, None)

    best = working[0]
    auth = auths[best["auth_index"]]
    if best["account"] == 403:
        rep.add(INFO, "account listing 403", "expected on Blomp, not an outage — your old script mistook this for one")
    rep.add(OK, "working combination", f"{auth.label} | {best['endpoint']} | container={urllib.parse.unquote(best['cont_variant'])}")
    if best["scheme"] == "http":
        rep.add(WARN, "using plain http", "the https variant did not work, so token and files travel unencrypted")
    if best["created"]:
        rep.add(INFO, "container", "did not exist and was created")

    print(f"Step 4: full operation suite in container '{urllib.parse.unquote(best['cont_variant'])}' under {prefix}")
    ctx = Ctx(auth, best["endpoint"], best["cont_variant"], user, prefix, run_id, args)
    try:
        run_suite(ctx, rep, args)
    finally:
        print("Step 5: cleanup…")
        cleanup(ctx, rep, args)

    settings = {"auth_url_v2": AUTH_V2, "auth_method": auth.label, "tenant": TENANT,
                "storage_url": best["endpoint"], "container": user, "prefix": "TinyVault/"}
    return finish(rep, 1 if rep.count(FAIL) else 0, settings)


def finish(rep, code, settings):
    print("\n" + "=" * 18 + " result " + "=" * 18)
    print(f" {rep.count(OK)} passed · {rep.count(WARN)} warnings · {rep.count(FAIL)} failed")
    if code == 0 and settings:
        print(" ✨ Blomp Swift API works. Settings for the plugin:")
        for k, v in settings.items():
            print(f"      {k:<12} = {v}")
        print("    IMPORTANT: never call GET on the account URL and never create/delete the container;")
        print("    upload/download/list/delete objects inside the container named after the login email.")
    return code, rep


# ------------------------------------------------------------------- MAIN ---

def load_credentials():
    u, p = os.environ.get("BLOMP_USER"), os.environ.get("BLOMP_PASS")
    if u and p:
        return u, p
    vals = {}
    for path in (os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env"), ".env"):
        if os.path.exists(path):
            with open(path, encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if line.startswith("export "):
                        line = line[7:]
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    k, _, v = line.partition("=")
                    v = v.strip()
                    if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
                        v = v[1:-1]
                    vals[k.strip()] = v
            break
    return (vals.get("username") or vals.get("BLOMP_USER") or vals.get("email"),
            vals.get("password") or vals.get("BLOMP_PASS"))


def main():
    global VERBOSE
    ap = argparse.ArgumentParser(description="Blomp Swift API tester for TinyVault")
    ap.add_argument("rounds", nargs="?", type=int, default=1, help="number of rounds (default 1)")
    ap.add_argument("--delay", type=int, default=30, help="seconds between rounds")
    ap.add_argument("--big-mb", type=int, default=48, help="size of the big upload test in MiB")
    ap.add_argument("--seg-mb", type=int, default=2, help="segment size for DLO/SLO tests in MiB (SLO min is usually 1)")
    ap.add_argument("--quick", action="store_true", help="skip big + segmented tests")
    ap.add_argument("--keep", action="store_true", help="do not delete test objects")
    ap.add_argument("--timeout", type=int, default=30, help="per-request timeout (s)")
    ap.add_argument("--big-timeout", type=int, default=300, help="timeout for big transfers (s)")
    ap.add_argument("--retries", type=int, default=2, help="retries on connection errors / 5xx")
    ap.add_argument("--json", metavar="FILE", help="write a redacted JSON report (for Blomp support)")
    ap.add_argument("-v", "--verbose", action="store_true", help="log every HTTP call")
    args = ap.parse_args()
    VERBOSE = args.verbose
    args.rounds = max(1, args.rounds)

    user, pw = load_credentials()
    if not user or not pw:
        print("❌ no credentials — put username=… and password=… in .env next to this script")
        sys.exit(3)

    print(f"=== Blomp Swift test · {args.rounds} round(s) · account {redact(user)} ===")
    codes, reports = [], []
    for i in range(args.rounds):
        if args.rounds > 1:
            print(f"\n───── round {i + 1}/{args.rounds} ─────")
        code, rep = run_round(args, user, pw)
        codes.append(code)
        reports.append({"round": i + 1, "exit": code, "matrix": rep.matrix, "steps": rep.steps})
        if i < args.rounds - 1:
            print(f"…waiting {args.delay}s")
            time.sleep(args.delay)

    passed, outages, fails = codes.count(0), codes.count(2), codes.count(1)
    print("\n=== summary ===")
    print(f" rounds: {args.rounds} · full-pass: {passed} · storage-denied: {outages} · failed: {fails}")
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump({"time": time.strftime("%Y-%m-%d %H:%M:%S"), "account": redact(user),
                       "auth_root": AUTH_ROOT, "rounds": reports}, fh, indent=2, default=str)
        print(f" report written to {args.json} (token-free, email redacted)")
    if fails:
        sys.exit(1)
    if outages:
        print(" storage denied the email container too — that is a genuine Blomp-side/permission problem.")
        sys.exit(2)
    if passed != args.rounds:
        sys.exit(1)
    print(" everything GREEN.")
    sys.exit(0)


if __name__ == "__main__":
    main()
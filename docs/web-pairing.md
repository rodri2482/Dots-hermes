# Pairing the web app by QR code

English translation of [appairage-web.md](appairage-web.md) (French original).

Version 1, 4 November 2026.

The web app (`web/`, hosted for instance on Vercel) is a static site. It knows no gateway in
advance: it is the phone, already paired, that hands its agents over when it scans a QR code. The
`DOTS_TOKEN` never leaves the phone: each browser receives a web token of its own, revocable from
the app.

```
Browser   ──(1) shows a QR: id + ephemeral public key
Phone     ──(2) scans, confirms, asks each gateway for a web token (web_token_create)
Phone     ──(3) encrypts {agents} for the browser's key, drops it on the relay  POST /api/pair?id=
Browser   ──(4) polls the drop  GET /api/pair?id= , decrypts, opens wss:// to each gateway
```

The relay (a Vercel function and an Upstash Redis) only sees ciphertext, kept 120 s at most and
erased on first read.

## 1. The QR code

Contents: a URL `https://<web app origin>/pair#v1.<id>.<pub>`

| Part | Format |
|---|---|
| `<origin>` | Origin of the web app (`https://dots.vercel.app`). In debug builds, the Android app also accepts `http://` toward `localhost`, `127.0.0.1`, `10.0.2.2` or a private IP (192.168.x, 10.x, 172.16-31.x). |
| `id` | 16 random bytes, base64url without `=` (22 characters). Serves as the address on the relay and as the HKDF salt. |
| `pub` | The browser's ECDH P-256 public key, uncompressed point (65 bytes, `0x04‖X‖Y`), base64url without `=` (87 characters). |

The browser regenerates `id` and its key pair every 30 s (a "spinning" QR); the private key, non
extractable, never leaves the tab. It polls the relay for the **current** `id` and the **previous**
one (a scan happening just before a rotation stays valid).

Scanned with a phone's camera when the app is not installed, the link simply opens the web app.

## 2. On the phone side

1. Parses the URL: path `/pair`, fragment `v1.<id>.<pub>`, `https` origin (see the debug exception).
2. Confirmation screen: "Connect a browser on **dots.vercel.app**?", with the list of the agents
   being handed over and the warning "Only scan a QR code shown on your own screen".
3. For each paired and connected agent, on its socket:
   `{"type":"web_token_create","ref":"<uuid>","origin":"<origin>","label":"<host of the origin> · <date>"}`.
   Reply `{"type":"web_token","ref","token","id"}` or
   `{"type":"web_token","ref","error":"origin_not_allowed","origin"}`.
   An agent that refuses the origin is flagged ("add `DOTS_WEB_ORIGINS=<origin>` on this gateway")
   and is not handed over.
4. Encrypts and drops the message (section 3) on `<origin>/api/pair?id=<id>`.

## 3. Encryption

Cleartext message (UTF-8 JSON):

```json
{
  "v": 1,
  "agents": [
    {
      "url": "wss://hermes.example.com/dots/ws",
      "token": "<web token>",
      "session_id": "<id of the web session>",
      "agent_id": "<agent_id from the welcome, or null>",
      "name": "Norti",
      "species": "galet | nuage | pave",
      "palette": "tangerine | bubblegum | sky | lime | sun | grape",
      "accessory": "winged_cap | none"
    }
  ],
  "lang": "fr",
  "issued_at": 1793800000000
}
```

`url` is always `wss://` (or `ws://` locally): the phone converts an address registered as
`https://` / `http://`, because the browser rejects the whole message otherwise.

Scheme (ECIES):

1. The phone generates an ephemeral P-256 key pair `(e, E)`.
2. `secret = ECDH(e, pub)`: the X coordinate, 32 bytes (`KeyAgreement("ECDH")` in Java,
   `deriveBits(…, 256)` in WebCrypto).
3. `key = HKDF-SHA256(ikm = secret, salt = decoded id (16 bytes), info = "dots-pair-v1", 32 bytes)`.
4. `ct = AES-256-GCM(key, iv = 12 random bytes, aad = "dots-pair-v1", cleartext)`: the 16-byte tag
   is appended at the end of the ciphertext (the default behaviour of Java and WebCrypto).

Dropped body (all the base64url of the protocol is **without `=`**:
`Base64.getUrlEncoder().withoutPadding()` in Java; the relay and the browser tolerate padding
anyway):

```json
{ "v": 1, "epk": "<E, 65 uncompressed bytes, base64url>", "iv": "<12 bytes, base64url>", "ct": "<ciphertext‖tag, base64url>" }
```

## 4. The relay

`web/api/pair.ts` (Vercel function), Upstash Redis storage through its REST API
(`KV_REST_API_URL` / `KV_REST_API_TOKEN`, or `UPSTASH_REDIS_REST_URL` / `UPSTASH_REDIS_REST_TOKEN`).

| Request | Reply |
|---|---|
| `POST /api/pair?id=<id>`, JSON body ≤ 16 KiB | `201` stored 120 s; `409` a drop already exists for this id; `400` invalid id; `413` too large |
| `GET /api/pair?id=<id>` | `200` + the dropped body, erased on read (`GETDEL`); `204` nothing yet |

`id`: exactly 22 characters `[A-Za-z0-9_-]`. Locally, `vite` serves the same API in memory
(`web/dev/relay.ts`).

## 5. The browser's connection to the gateway

- `new WebSocket(url, ["dots.v1", "dots.token." + token])`. The gateway replies with the
  subprotocol `dots.v1`.
- The gateway only accepts a browser if its `Origin` header is in `DOTS_WEB_ORIGINS` (a
  comma-separated list). `install.sh` puts the main web app origin there (`--default-web-origin` or
  `CLAYDOT_WEB_ORIGIN`, otherwise the official app, `https://dots-hermes.vercel.app`), keeping the
  origins already present and those passed with `--web-origin`. Otherwise: HTTP 403.
- Unknown or revoked web token: the connection opens then immediately closes with code **4401**
  (after a `{"type":"error","code":"unauthorized"}` frame). The browser then forgets that agent.
- Afterwards, the protocol is the app's ([protocole-gateway.md](protocole-gateway.md)), without
  `push_token`.

## 6. Web sessions (phone)

| Frame | Effect |
|---|---|
| `web_sessions_get` | Reply `web_sessions`: `sessions[]` = `{id, label, origin, created_at, last_seen}` |
| `web_revoke` `{id}` | Revokes the session, closes its sockets (4401), returns `web_sessions` |

A browser can neither create tokens nor list sessions; it can only revoke its own (`web_revoke`
without `id`), to sign out.

The gateway only keeps the SHA-256 fingerprint of each web token
(`platforms/dots/web_tokens.json`, mode 600), 20 sessions at most (the least recently used goes
first).

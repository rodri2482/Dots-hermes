# Appairage de l'app web par QR code

*Traduction anglaise : [web-pairing.md](web-pairing.md).*

Version 1, 4 novembre 2026.

L'app web (`web/`, hébergée par exemple sur Vercel) est un site statique. Elle ne connaît aucun
gateway à l'avance : c'est le téléphone, déjà appairé, qui lui transmet ses agents en scannant un QR
code. Le `DOTS_TOKEN` ne quitte jamais le téléphone : chaque navigateur reçoit un jeton web à lui,
révocable depuis l'app.

```
Navigateur ──(1) affiche un QR : id + clé publique éphémère
Téléphone  ──(2) scanne, confirme, demande un jeton web à chaque gateway (web_token_create)
Téléphone  ──(3) chiffre {agents} pour la clé du navigateur, dépose sur le relais  POST /api/pair?id=
Navigateur ──(4) relève le dépôt  GET /api/pair?id= , déchiffre, ouvre wss:// vers chaque gateway
```

Le relais (une fonction Vercel et un Redis Upstash) ne voit que du texte chiffré, gardé 120 s au plus
et effacé à la première lecture.

## 1. Le QR code

Contenu : une URL `https://<origine de l'app web>/pair#v1.<id>.<pub>`

| Partie | Format |
|---|---|
| `<origine>` | Origine de l'app web (`https://dots.vercel.app`). En build debug, l'app Android accepte aussi `http://` vers `localhost`, `127.0.0.1`, `10.0.2.2` ou une IP privée (192.168.x, 10.x, 172.16-31.x). |
| `id` | 16 octets aléatoires, base64url sans `=` (22 caractères). Sert d'adresse sur le relais et de sel HKDF. |
| `pub` | Clé publique ECDH P-256 du navigateur, point non compressé (65 octets, `0x04‖X‖Y`), base64url sans `=` (87 caractères). |

Le navigateur régénère `id` et sa paire de clés toutes les 30 s (QR « tournant ») ; la clé privée,
non extractible, ne quitte pas l'onglet. Il interroge le relais pour l'`id` courant **et** le
précédent (un scan juste avant la rotation reste valable).

Scanné avec l'appareil photo d'un téléphone sans l'app, le lien ouvre simplement l'app web.

## 2. Côté téléphone

1. Analyse l'URL : chemin `/pair`, fragment `v1.<id>.<pub>`, origine `https` (voir l'exception debug).
2. Écran de confirmation : « Connecter un navigateur sur **dots.vercel.app** ? », avec la liste des
   agents transmis et l'avertissement « Ne scanne qu'un QR code affiché sur ton propre écran ».
3. Pour chaque agent appairé et connecté, sur sa socket : `{"type":"web_token_create","ref":"<uuid>","origin":"<origine>","label":"<hôte de l'origine> · <date>"}`.
   Réponse `{"type":"web_token","ref","token","id"}` ou `{"type":"web_token","ref","error":"origin_not_allowed","origin"}`.
   Un agent qui refuse l'origine est signalé (« ajoute `DOTS_WEB_ORIGINS=<origine>` sur ce gateway ») et n'est pas transmis.
4. Chiffre et dépose le message (section 3) sur `<origine>/api/pair?id=<id>`.

## 3. Chiffrement

Message en clair (JSON UTF-8) :

```json
{
  "v": 1,
  "agents": [
    {
      "url": "wss://hermes.example.com/dots/ws",
      "token": "<jeton web>",
      "session_id": "<id de la session web>",
      "agent_id": "<agent_id du welcome, ou null>",
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

`url` est toujours en `wss://` (ou `ws://` en local) : le téléphone convertit une adresse enregistrée en `https://` / `http://`, car le navigateur rejette tout le message sinon.

Schéma (ECIES) :

1. Le téléphone génère une paire de clés P-256 éphémère `(e, E)`.
2. `secret = ECDH(e, pub)` : la coordonnée X, 32 octets (`KeyAgreement("ECDH")` en Java, `deriveBits(…, 256)` en WebCrypto).
3. `clé = HKDF-SHA256(ikm = secret, salt = id décodé (16 octets), info = "dots-pair-v1", 32 octets)`.
4. `ct = AES-256-GCM(clé, iv = 12 octets aléatoires, aad = "dots-pair-v1", clair)` : le tag de 16 octets est ajouté à la fin du chiffré (comportement par défaut de Java et de WebCrypto).

Corps déposé (tout le base64url du protocole est **sans `=`** : `Base64.getUrlEncoder().withoutPadding()` en Java ; le relais et le navigateur tolèrent quand même le padding) :

```json
{ "v": 1, "epk": "<E, 65 octets non compressés, base64url>", "iv": "<12 octets, base64url>", "ct": "<chiffré‖tag, base64url>" }
```

## 4. Le relais

`web/api/pair.ts` (fonction Vercel), stockage Upstash Redis via son API REST
(`KV_REST_API_URL` / `KV_REST_API_TOKEN`, ou `UPSTASH_REDIS_REST_URL` / `UPSTASH_REDIS_REST_TOKEN`).

| Requête | Réponse |
|---|---|
| `POST /api/pair?id=<id>`, corps JSON ≤ 16 Kio | `201` stocké 120 s ; `409` un dépôt existe déjà pour cet id ; `400` id invalide ; `413` trop gros |
| `GET /api/pair?id=<id>` | `200` + le corps déposé, effacé à la lecture (`GETDEL`) ; `204` rien pour l'instant |

`id` : exactement 22 caractères `[A-Za-z0-9_-]`. En local, `vite` sert la même API en mémoire (`web/dev/relay.ts`).

## 5. Connexion du navigateur au gateway

- `new WebSocket(url, ["dots.v1", "dots.token." + token])`. Le gateway renvoie le sous-protocole `dots.v1`.
- Le gateway n'accepte un navigateur que si son en-tête `Origin` figure dans `DOTS_WEB_ORIGINS`
  (liste séparée par des virgules). `install.sh` y met l'origine de l'app web principale
  (`--default-web-origin` ou `CLAYDOT_WEB_ORIGIN`, sinon l'app officielle
  `https://dots-hermes.vercel.app`), en gardant les origines déjà présentes et celles passées avec
  `--web-origin`. Sinon : HTTP 403.
- Jeton web inconnu ou révoqué : la connexion s'ouvre puis se ferme aussitôt avec le code **4401**
  (après une trame `{"type":"error","code":"unauthorized"}`). Le navigateur oublie alors cet agent.
- Ensuite, le protocole est celui de l'app (`docs/protocole-gateway.md`), sans `push_token`.

## 6. Sessions web (téléphone)

| Trame | Effet |
|---|---|
| `web_sessions_get` | Réponse `web_sessions` : `sessions[]` = `{id, label, origin, created_at, last_seen}` |
| `web_revoke` `{id}` | Révoque la session, ferme ses sockets (4401), renvoie `web_sessions` |

Un navigateur ne peut ni créer de jeton ni lister les sessions ; il peut seulement révoquer la
sienne (`web_revoke` sans `id`), pour se déconnecter.

Le gateway ne garde que l'empreinte SHA-256 de chaque jeton web (`platforms/dots/web_tokens.json`,
mode 600), 20 sessions au plus (la moins récemment utilisée part en premier).

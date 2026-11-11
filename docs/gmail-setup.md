# Gmail setup (owner, once)

Claydot reads Gmail **read-only** (`gmail.readonly`): it can search and read emails when you ask, never
send, delete or change them. Google only lets an app ask for that access once the app is registered in
a Google Cloud project. No secret goes into the app: Google recognises Claydot by its package name and
the fingerprint of the key that signs the APK.

Until this is done, *Connect Gmail* in the settings shows "Gmail isn't set up for this app yet".

## 1. Project and API (5 min)

1. Open <https://console.cloud.google.com/>, create a project (for example `claydot`).
2. *APIs & Services → Library*: search **Gmail API**, then **Enable**.

## 2. Consent screen (5 min)

*APIs & Services → OAuth consent screen* (Google may call it *Google Auth Platform*):

- User type: **External**. App name: `Claydot`. Support and developer email: yours.
- Data access / scopes: add `.../auth/gmail.readonly`.
- Audience: leave the app in **Testing** and add your own Gmail address under **Test users**.
  Up to 100 test users can connect without Google's verification; a public release would need it.

## 3. Android client (5 min)

*Credentials → Create credentials → OAuth client ID → Android*:

| Field | Value |
|---|---|
| Package name | `app.claydot` |
| SHA-1 certificate fingerprint | the fingerprint of the key that signs the APK you install |

For the current test builds (signed with this PC's Android debug key) the SHA-1 is:

```
2F:18:7A:F3:98:41:FC:97:21:9A:4B:57:48:6E:B3:8E:94:EE:AE:E2
```

When releases are signed with your own release key, add a second Android client with that key's
SHA-1 (shown by `keytool -list -v -keystore claydot-release.jks`). The offline test build
(`app.claydot.offline`) needs its own client only if you want Gmail there too.

## 4. Try it

1. Install the new APK, open a Groq chat → **•••** → **Gmail** → **Connect Gmail**.
2. Pick your Google account and accept "Read your email messages and settings" (read-only).
3. Ask: "Any new emails from my bank this week?"

Disconnecting in the settings revokes the access at Google. You can also remove it any time at
<https://myaccount.google.com/permissions>.

## Safety notes

- Email content is written by other people. Claydot passes it to the model as data, with an explicit
  instruction never to follow requests found inside it, and Claydot has no tool that can send, delete or
  act on anything.
- Only Groq accounts use Gmail (through Groq's function calling). Each message allows at most four
  Gmail look-ups, to keep cost and latency bounded.
- Email text goes to Groq to be answered, like the rest of the conversation.

## Release builds (GitHub releases)

Releases are signed with the Claydot release key, not the debug key, so Google needs a second
Android OAuth client in the same Google Cloud project:

- Package name: `app.claydot`
- SHA-1: `85:CF:34:D7:52:ED:9B:13:D1:51:23:86:35:EE:33:4A:BB:7C:EA:47`

If it is missing, Settings → Gmail shows the setup error together with the exact fingerprint of the
installed build, which can be copied from there.

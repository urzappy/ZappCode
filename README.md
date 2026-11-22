# ZappCode

**Your own tunnel. Your own agent. One phone.**

ZappCode is an Android client for an OpenCode server, with its own interface and its own network
guard. The design, the branding and the tunnel are ZAPP's. This repository holds **downloads
only** — the source is private.

---

## Download

**[Download ZappCode-1.0.apk](../../releases/latest)**

``
version   1.0  (versionCode 18)
size      122252043 B
sha256    6e0b517dd282271202d1b8417bb029c6fab29c88a2571ef6a0bec2fee4f16ba9
signer    CN=ZappCode  (private key - not the shared Android debug key)
``

Check the file you downloaded matches that hash before installing.

Termux is **inside this APK** (F-Droid 0.118, 101 739 523 B), so there is nothing else to download
and no store to visit. The app installs it for you.

## Requirements

| What | Why |
| --- | --- |
| Android 8.0 (API 26) or newer | minimum supported version |
| ~500 MB free space | the app, the Termux it installs, and the engine |
| A network route | Wi-Fi or mobile data; the Guard provides the tunnel |

Nothing else. Termux ships inside the app, and the engine is fetched automatically on first
start.

---

## Install

1. Download `ZappCode-1.0.apk`.
2. Open it. Android will say *"For your security, your phone is not allowed to install unknown
   apps from this source."*
3. Tap **Settings** → turn on **Allow from this source** → back → **Install**.
4. Open **ZappCode**.

That warning appears for every app that does not come from the Play Store. It is not a sign the
file is damaged.

## First run

The app walks you through three things, in order:

**1. Consent.** Read it and accept. Nothing is stored before this.

**2. Identity.** The app derives an identity from your address and keeps only a hash of it. The
plain address is never written to disk — it lives in memory for the length of that screen and is
dropped when it closes.

**3. Termux access.** The app carries Termux inside it. Tap **Install Termux (bundled)** — Android
will ask once to allow installs from ZAPP, then the installer opens. Install it, come back, and the
app flips to *installed* by itself. Then tap **Allow ZAPP to run commands**: that permission is what
lets ZAPP start the server. If you deny it, the app can start nothing and will show *"Access ended"*.

Your device token is generated on the phone at this point. It never leaves the device.

On first start the app also fetches the engine (about 121 MB) into Termux and installs
`glibc-runner` if Termux does not have it yet. That happens once.

---

## Guard — get a working route

Open **Guard**.

Tap **Repair (one tap)**. Five stages run in order, and each one has to prove itself before the
next starts:

```
1  Disconnect        drop the current tunnel
2  Fix WARP keys     regenerate the registration
3  Reconnect         bring the tunnel back up
4  Refresh route     re-read the egress address
5  Verify WARP       confirm traffic actually leaves through it
```

When it finishes you have a verified Cloudflare WARP address and the tab shows your public `/64`.

**Rotate /64 Now** gives you a different address from the warm pool. Rotation is deliberately
separate from Repair — repairing a broken route and changing identity are different decisions.

## Chat — talk to the agent

Open **Chat**.

- **New session** starts a conversation.
- Pick an existing session from the sidebar to resume it.
- Type and send. Replies stream in token by token as the server produces them.

What you will see inline:

| Element | Meaning |
| --- | --- |
| **Thinking** | the model's reasoning, above the answer |
| **Working** | a tool is running right now |
| Tool cards | shell commands, file reads, edits, diffs |
| Diff rows | exactly what changed, file by file |
| Permission cards | the agent is asking before it acts |

## Long commands — move them to the background

When the agent starts something slow, tap **Move to background** on the running card.

The command keeps running. The turn stops being pinned to it, so you can keep chatting. Everything
detached lives in the **Shell** sheet — reachable from the chip above the composer.

## Export and import

Sessions export to `Download/ZAPP/` as JSON.

- 1 GB per file, unlimited count
- The picker opens inside `Download/ZAPP`
- Import reads from the same folder

---

## Troubleshooting

**"Access ended — this device is no longer allowed on the server"**
The server on port 4096 authenticates with your app's device token. If a server from an older
install is still running, it holds the *old* token and refuses the new app. Build 4 fixes this:
the launcher now replaces a server whose password does not match. If you still see it, open Termux
and run `pkill -f "opencode serve"`, then reopen ZappCode.

**"Server unreachable"**
No route. Open **Guard** and run **Repair (one tap)**. Nothing was deleted.

**Guard repair stops at a stage**
Each stage has to prove itself, so a stop means that stage failed. Run it again; a route that
failed verification once usually passes on the second attempt. If it fails on the same stage twice,
the tunnel is up but the exit is not verified — rotate the `/64` and repair again.

**Replies stop mid-answer**
The app watches for a dead stream and recovers on its own. If the turn ends without an answer,
send again; the session state is on the server, not in the app.

---

## What's inside

- Streaming chat with tool calls, diffs, file rows and permissions
- Terminal / PTY tab with an xterm-compatible renderer
- Guard: connect, disconnect, rotate `/64`, one-tap repair that proves every stage
- Warm pool of registered WARP identities
- Background shells — move running work away without stopping it
- Session export / import, 1 GB per file, unlimited count
- In-app notifications only, by design

## Notes

- The launcher and proxy scripts ship AES-256-GCM encrypted inside the APK. The archive contains
  no plaintext script of any kind.
- The release build is not debuggable: no `run-as`, no debugger attach.
- The APK is signed with a private key, so a modified build cannot install as an update over it.
- Not affiliated with OpenCode, Cloudflare, or any other project named here.

## Community

Discord: **https://discord.gg/RQ6NVspT2u**

Bugs and requests: [Issues](../../issues).

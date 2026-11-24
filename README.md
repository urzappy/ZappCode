# ZappCode

**Your own tunnel. Your own agent. One phone.**

ZappCode is an Android client for an OpenCode server, with its own interface and its own network
guard. The design, the branding and the tunnel are ZAPP's. This repository holds **downloads
only** - the APK and its source archive.

---

## Download

**[Download ZappCode-1.0.94.apk](../../releases/latest)**

```
version   1.0.94  (versionCode 95)
size      134502515 B
sha256    7ef8c397115dd4b07631bf1cebb77eb29ce3a3d38b7181132f8494d6f1d89c1f
signer    CN=ZappCode  (private key - not the shared Android debug key)
termux    F-Droid 0.118.3 bundled   113 880 067 B
engine    fetched on first run      121 172 970 B
```

Check the file you downloaded matches that hash before installing.

Termux is **inside this APK**, so there is nothing else to download and no store to visit. The app
unpacks it and hands it to Android's installer for you.

## What's new in 1.0.94

- **Every download now reports its own exact size.** Termux unpacking shows `x.x of 113.9 MB`, the
  guard lane `x.x of 0.4 MB`, the engine `x.x of 121.1 MB`. Before this build the lane's 403,072 B
  was printed against the engine's 121.1 MB, which read as a stuck `0.4 MB`.
- **The bar moves during every byte-counted stage**, not only while the engine archive lands, and
  byte stages refresh every 2 s instead of every 5 s.
- **Sign in with OpenCode** from the quota card: device-code flow, same copy as the desktop client.

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

1. Download `ZappCode-1.0.94.apk`.
2. Open it. Android will say *"For your security, your phone is not allowed to install unknown
   apps from this source."*
3. Tap **Settings** -> turn on **Allow from this source** -> back -> **Install**.
4. Open **ZappCode**.

That warning appears for every app that does not come from the Play Store. It is not a sign the
file is damaged.

**Updating:** install over the old version. Do not uninstall first - uninstalling deletes your
sessions' local state and the engine inside Termux.

## First run

The app walks you through four things, in order, and each step ticks only when the phone proves it:

**1. Install Termux.** The app carries Termux (F-Droid 0.118.3, 113 880 067 B) inside itself. Tap
**Install Termux (bundled)** - Android asks once to allow installs from ZAPP, then the installer
opens. The screen shows the unpack as it happens: `42.7 of 113.9 MB`. Install it, come back, and
the step flips to *installed* by itself.

**2. Allow ZAPP to run commands.** `com.termux.permission.RUN_COMMAND` is what lets ZAPP start the
server. If you deny it, the app can start nothing and will show *"Access ended"*.

**3. Paste one line in Termux.** Termux only accepts commands from other apps when
`allow-external-apps=true` is in `~/.termux/termux.properties`. Android does not let one app write
another app's private home, so this is the single thing the app cannot do for you. Tap **Copy
line**, open Termux, paste it, press Enter. It is one line, once per Termux install, and it takes
effect on the very next command - no Termux restart. The screen then probes and turns green.

**4. Start the engine.** The first run downloads the engine archive (121 172 970 B) into Termux,
unpacks it, installs `glibc-runner`, and starts the server. The screen shows the real stage, the
real bytes on disk, and the last lines Termux printed. This happens once; every later start takes
seconds.

Your device token is generated on the phone at this point. It never leaves the device.

---

## Guard - get a working route

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
separate from Repair - repairing a broken route and changing identity are different decisions.

## Chat - talk to the agent

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

## Signing in

The quota card on the Guard screen carries **Sign in with OpenCode**. It opens the console with a
device code already in the link; confirm there and the card flips off `pending` on its own. No key
to paste, no account file to copy.

## Long commands - move them to the background

When the agent starts something slow, tap **Move to background** on the running card.

The command keeps running. The turn stops being pinned to it, so you can keep chatting. Everything
detached lives in the **Shell** sheet - reachable from the chip above the composer.

## Export and import

Sessions export to `Download/ZAPP/` as JSON.

- 1 GB per file, unlimited count
- The picker opens inside `Download/ZAPP`
- Import reads from the same folder

---

## Troubleshooting

**"Access ended - this device is no longer allowed on the server"**
The server on port 4096 authenticates with your app's device token. If a server from an older
install is still running, it holds the *old* token and refuses the new app. The launcher replaces a
server whose password does not match. If you still see it, open Termux and run
`pkill -f "opencode serve"`, then reopen ZappCode.

**"Server unreachable"**
No route. Open **Guard** and run **Repair (one tap)**. Nothing was deleted.

**Step 4 says `nothing downloaded yet` and never changes**
Termux cannot read the staged scripts. That is almost always a missing storage grant: Android
Settings -> Apps -> Termux -> Permissions -> **Files and media: allow all**, then reopen ZappCode. The
boot log at `Download/zapp_boot.log` shows the exact line that failed.

**Guard repair stops at a stage**
Each stage has to prove itself, so a stop means that stage failed. Run it again; a route that
failed verification once usually passes on the second attempt. If it fails on the same stage twice,
the tunnel is up but the exit is not verified - rotate the `/64` and repair again.

**Replies stop mid-answer**
The app watches for a dead stream and recovers on its own. If the turn ends without an answer,
send again; the session state is on the server, not in the app.

---

## What's inside

- Streaming chat with tool calls, diffs, file rows and permissions
- Terminal / PTY tab with an xterm-compatible renderer
- Guard: connect, disconnect, rotate `/64`, one-tap repair that proves every stage
- Warm pool of registered WARP identities
- Exact-size progress for Termux unpacking, the guard lane and the engine archive
- OpenCode account sign-in (device code) from the quota card
- Background shells - move running work away without stopping it
- Session export / import, 1 GB per file, unlimited count
- In-app notifications only, by design

## Notes

- The launcher and proxy scripts ship AES-256-GCM encrypted inside the APK. The archive contains
  no plaintext script of any kind.
- The release build is not debuggable: no `run-as`, no debugger attach.
- The APK is signed with a private key, so a modified build cannot install as an update over it.
- The Termux copy bundled here is unmodified F-Droid 0.118.3, verified by SHA-256 before it is
  offered to the installer.
- Not affiliated with OpenCode, Cloudflare, or any other project named here.

## Community

Discord: **https://discord.gg/RQ6NVspT2u**

Bugs and requests: [Issues](../../issues).

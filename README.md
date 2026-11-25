# ZappCode

**Your own tunnel. Your own agent. One phone.**

ZappCode is an Android client for an OpenCode server, with its own interface and its own network
guard. The design, the branding and the tunnel are ZAPP's. This repository holds **downloads only** -
the APK, its bundled Termux and the engine archive.

---

## Download

**[Download the latest ZappCode](../../releases/latest)**

```
version   1.0.174  (versionCode 174)
size      134831903 B
sha256    d9b1325e3786737f2bfad438d4a19eadf1c58a93cef529d7ccafb0aad3ce0d77
md5       133aac0ba28f7db02889d4e285d75aae
signer    CN=ZappCode  (private key - not the shared Android debug key)
termux    F-Droid 0.118.3 bundled   113 880 067 B
engine    fetched on first run      121 172 970 B
```

Check the file you downloaded matches that hash before installing.

Termux is **inside this APK**, so there is nothing else to download and no store to visit. The app
unpacks it and hands it to Android's installer for you.

Every release is published here and nowhere else. There is no Play Store listing, no mirror, and no
third-party build.

## Updates

ZappCode asks the release channel once each time it starts, and tells you when a newer version
exists. It never installs anything behind your back.

- **On the first open after a release**, a dialog states the installed version, the new version, the
  download size and what changed. **Update now** continues to the download sheet; **Later** means
  that release is never announced again.
- **The version is always visible** in the title strip, and beside **Settings** in the sidebar. A dot
  next to it means a release is waiting.
- Nothing downloads until you tap **Download**; nothing installs until you tap **Install** and
  confirm in Android's installer.
- **Your data survives.** The installer replaces the APK in place. It does not uninstall, so
  sessions, settings, the engine and the guard lane all stay exactly where they are.
- **Download new versions automatically** can be switched on in that sheet. The app then fetches the
  APK in the background and checks its SHA-256 before offering it; Android still asks before
  installing, because that confirmation cannot be skipped by any app.
- A download is discarded unless its SHA-256 matches the hash published in the release notes, so a
  cut-off or swapped file never reaches the installer.

## What's new in 1.0.174

### The free limit belongs to the address

A free-tier refusal is metered against the egress `/64` the request left from. Two different
prefixes have two different budgets, and the app measures both:

- The stated window is stored **with the prefix that earned it**. When a hop installs a proven
  fresh address, the held prompt continues immediately instead of waiting out a window that
  belongs to an address it is no longer using.
- The clock is written the moment a refusal is seen, before any other path can return, so the
  automatic resume can never resend into the same wall it is already waiting on.
- A refusal that states a window longer than 15 minutes is a **clock**, and a clock is waited
  out on that prefix. A refusal inside the compaction band is a **size** wall, and a size wall
  compacts. Neither one hops.
- Your own taps are never gated. Only the automatic resend waits.

### Five proven slots, unlimited parked supply

The reserve is back to the shape that behaved: **five** proven identities, and a parked queue
that is not capped. Parking is a registration - one HTTPS round trip that never touches the
tunnel - so the supply can grow while a reply streams. Proving wears an identity and rebuilds
the interface, so it happens only when no turn is live, no verify is in flight, and fewer than
five slots are proven.

```
[pool] prove skipped: reserve 5/5 (proofs 0m,18m,24m,26m,45m) is full
```

### Verified on open

A proof is stored with the identity that earned it and restored at launch. Reopening the app on
the same registration shows the tunnel as verified immediately, instead of spending the first
seconds on "verifying".

### Two ways out of a long turn

- **Jump to latest** returns to the live end. Both pills read the scroll layout, not a remembered
  intent, so a list that was already overflowing when a turn began still follows the bottom.
- **Thinking** floats at the top: one tap goes to the start of the current turn, where the
  reasoning row begins; a second tap goes to the start of the conversation.

### "Verified" but nothing arrives

The tunnel could report a verified WARP address while carrying nothing, and every repair path
refused to touch it. The cause was in how a live turn was recognised: a refused turn retries every
few seconds, and each retry counted as evidence that the turn was still producing - so the "live
turn" clock never aged out and the dead tunnel was never rebuilt.

- A failed step is no longer treated as a live turn.
- A turn that has failed and produced nothing for 20 seconds stops blocking repairs. A retry that
  actually produces text re-arms it, so real work is never cut.

### Boot no longer rebuilds the identity a dozen times

On a fresh start the app could tear down and rebuild its tunnel repeatedly - measured twelve full
identity rebuilds in 68 seconds on one launch, with the screen stuck on "verifying" throughout.

- The re-roll bound is now a real bound. It used to reset itself the moment it was reached, which
  made it infinite.
- Re-rolling the *edge* to escape a used *`/64`* is gone. The `/64` is bound to the tunnel interface,
  not to the edge, so that could never have worked.
- While a stated free-limit window is open, a refused prefix is not re-rolled: the window is
  waited out on the prefix that earned it, and a hop that installs a proven fresh address
  resumes the held prompt instead of waiting for a window that no longer applies.

### Steering

Sending while the agent is still answering now follows the setting you chose.

- **Steer** sends without interrupting the running turn. **Queue** waits its turn. The default is
  steer, matching the desktop.
- A turn the app did not start - resumed after a limit, after a compaction, or on relaunch - now
  looks live, so a message typed during it steers instead of interrupting.

### Compaction

Compaction now uses the engine's own ceiling instead of a guessed ratio: 85% of the model's context
window, computed from the same reserve the server uses. A conversation that reaches that ceiling is
compacted before the send rather than refused after it.

### Around the app

- An **update dialog** on the first open after a release, with the version, the size and what
  changed.
- The **version** shown in the title strip and in the sidebar, read from the installed package so it
  can never drift from the build.
- An **update row beside Settings**, and a dot in the title strip when a release is waiting.
- **Download**, **Community** and **Support** sections in About, all pointing at real addresses.
- Fresh-install onboarding verified end to end on a wiped device: the app installed its bundled
  Termux by itself, with no browser and no store visit.

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

1. Download the APK from [the latest release](../../releases/latest).
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

A route is only "verified" when a real request leaves through it and comes back. A handshake alone
is not proof, and the app does not treat it as one.

## Chat - talk to the agent

Open **Chat**.

- **New session** starts a conversation.
- Pick an existing session from the sidebar to resume it.
- Type and send. Replies stream in token by token as the server produces them.
- Type while a reply is still arriving and it **steers** - it joins the running turn instead of
  interrupting it. Set it to **Queue** instead in Settings if you prefer.

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
Storage is not involved. The app hands the scripts to Termux over loopback, so there is no
permission to grant and no folder to point at. If it still stalls, Termux has no route for its own
downloads: switch between Wi-Fi and mobile data, then tap the button again. Nothing was fetched yet,
so nothing is half-installed.

**Guard repair stops at a stage**
Each stage has to prove itself, so a stop means that stage failed. Run it again; a route that
failed verification once usually passes on the second attempt. If it fails on the same stage twice,
the tunnel is up but the exit is not verified - rotate the `/64` and repair again.

**Replies stop mid-answer**
The app watches for a dead stream and recovers on its own. If a turn ends without an answer, send
again; the session state is on the server, not in the app.

**"Free usage limit reached"**
That is the provider's own budget for the free tier, and it clears on a clock - not by changing
your address. The app holds your prompt and continues by itself when the window opens; the card
shows how long is left. Changing your `/64` will not shorten it.

---

## What's inside

- Streaming chat with tool calls, diffs, file rows and permissions
- Steer or queue while the agent is working, following your setting
- Terminal / PTY tab with an xterm-compatible renderer
- Guard: connect, disconnect, rotate `/64`, one-tap repair that proves every stage
- Warm pool of registered WARP identities
- Compaction at the engine's own ceiling, so a long conversation keeps going
- Exact-size progress for Termux unpacking, the guard lane and the engine archive
- Engine scripts handed to Termux over loopback - no storage permission required
- OpenCode account sign-in (device code) from the quota card
- Background shells - move running work away without stopping it
- Session export / import, 1 GB per file, unlimited count
- In-app update check with a verified download and a version label on every screen
- In-app notifications only, by design

## Notes

- The launcher and proxy scripts ship AES-256-GCM encrypted inside the APK. The archive contains
  no plaintext script of any kind.
- The release build is not debuggable: no `run-as`, no debugger attach.
- The APK is signed with a private key, so a modified build cannot install as an update over it.
- The Termux copy bundled here is unmodified F-Droid 0.118.3, verified by SHA-256 before it is
  offered to the installer.
- Not affiliated with OpenCode, Cloudflare, or any other project named here.

## Support

ZappCode is free and has no subscription, no ads, and no account of its own. The release channel
and the relay cost money to run; if the app saves you time, a donation keeps them alive.

- **Donate:** see the **Support** section inside the app (About, and the update dialog).
- **Discord:** https://discord.gg/RQ6NVspT2u
- **Bugs and requests:** [Issues](../../issues)

## Community

Discord: **https://discord.gg/RQ6NVspT2u**

That is the fastest place to report a bug, ask for a build, or find out when the next release lands.

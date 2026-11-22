# ZappCode

**Your own tunnel. Your own agent.**

ZappCode is a native Android client for an OpenCode server, with its own interface and its own
network guard. Design, branding and the tunnel are ZAPP's.

This repository holds **downloads only**. The source is private.

---

## Download

Get the latest APK from [Releases](../../releases/latest).

1. Download `ZappCode-1.0.apk`.
2. Open it on the phone and allow install from this source when asked.
3. Launch **ZappCode**.

Updates install over the previous build and keep your settings.

## How to use

**1. Guard — get an address.**
Open **Guard** and tap **Repair (one tap)**. Five stages run in order and each has to prove
itself: Disconnect, Fix WARP keys, Reconnect, Refresh route, Verify WARP. When it finishes you
have a verified Cloudflare WARP address and the tab shows your public `/64`.

**2. Chat — talk to the agent.**
Open **Chat**, pick a session or start a new one, and type. Replies stream as they arrive.
Thinking is shown above the answer; tools, diffs and file rows appear inline.

**3. Long commands — move them to the background.**
When the agent starts a command that takes a while, tap **Move to background** on the running
card. The command keeps running and the turn stops being pinned to it. Everything detached
lives in the **Shell** sheet, reachable from the chip above the composer.

**4. Rotate when a route dies.**
**Rotate /64 Now** on Guard gives you a new address from the pool. Rotation is deliberately
separate from Repair — repairing a route and changing identity are different decisions.

**5. Export and import.**
Sessions export to `Download/ZAPP/` as JSON, 1 GB per file, unlimited count. Import reads from
the same folder.

## What's inside

- Streaming chat with tool calls, diffs, file rows and permissions
- Terminal / PTY tab with an xterm-compatible renderer
- Guard: connect, disconnect, rotate `/64`, one-tap repair that proves every stage
- Warm pool of registered WARP identities
- Background shells, move running work to background without stopping it
- Session export / import, 1 GB per file, unlimited count
- In-app notifications only, by design

## Community

Discord: **https://discord.gg/RQ6NVspT2u**

Bugs and requests: [Issues](../../issues).

## Notes

- The launcher and proxy scripts ship AES-256-GCM encrypted inside the APK. The archive contains
  no plaintext script of any kind.
- The release build is not debuggable: no `run-as`, no debugger attach.
- Not affiliated with OpenCode, Cloudflare, or any other project named here.

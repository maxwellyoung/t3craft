# Your first T3 Craft session

This is an early Fabric mod, not a replacement for T3 Code. Use a disposable Minecraft world for your first try. Office construction changes blocks and requires command permission.

## Before you start

- Minecraft Java 26.3, Fabric Loader 0.19.5+, Fabric API for 26.3, and the [0.5.0 release jar](https://github.com/maxwellyoung/t3craft/releases/tag/v0.5.0).
- A running T3 Code environment with Settings → Connections. T3's client protocol may change; the README records the tested nightly versions.
- For Prism, select Java 25 for the 26.3 profile. Keep your existing launcher profiles and worlds intact.

## The useful first five minutes

1. Launch the Fabric profile and enter your disposable world.
2. In T3 Code, create a pairing link under Settings → Connections. Keep it private: do not paste it into game chat, a bug report, or a message to an agent.
3. Press **`** or run `/t3`. Choose **Paste link → Pair machine** in the private setup form. Check that the expected machine and thread titles appear.
4. Open an existing harmless thread. Send a small request you would ordinarily make in T3, then return to the game. Confirm the completion or decision ping opens that thread.
5. If it has a ready checkpoint, choose **Review**. Inspect a changed file and its patch. A checkpoint is not proof that tests passed or changes were merged.
6. Optional: `/t3 office` builds the office in this disposable world. Pin a thread to keep its desk. Leave agent world-building off unless you choose to enable it.

## If something fails

Open **Connections** to see the affected machine's status. Retry that connection; replace an expired pairing with a new link. Unsupported protocol errors require an update rather than repeated pairing. Removal is local; revoke the Minecraft device separately in T3 settings when finished.

A useful issue report includes mod version, Minecraft/Fabric versions, T3 version, operating system, the step that failed and a redacted screenshot. Never include a pairing link, token, private conversation or full unreviewed config/log.

## Tell us whether it earns its place

Could you pair without help? Did a real ping, decision or checkpoint save an app switch? Did you reopen it on another day without being reminded? Those answers matter more than a star.

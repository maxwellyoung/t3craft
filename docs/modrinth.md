# Modrinth listing (ready to paste)

Draft copy for the T3 Craft project page on Modrinth. Nothing here is published yet.

## Project settings

| Field | Value |
| --- | --- |
| Name | T3 Craft |
| Slug | `t3craft` |
| Project type | Mod |
| Summary (max 256 chars) | Run T3 Code agents from inside Minecraft: send a prompt, keep mining, and get pinged when the agent finishes, needs an approval, or has a question. |
| Client side | Required |
| Server side | Optional (only for the shared server village) |
| Loaders | Fabric |
| Game versions | 26.3 |
| License | MIT (`LICENSE` in the repo) |
| Source | https://github.com/maxwellyoung/t3craft |
| Issues | https://github.com/maxwellyoung/t3craft/issues |
| Primary category | Utility |
| Additional categories | Management, Social |
| Icon | `src/main/resources/assets/t3craft/icon.png` (128x128). Modrinth accepts it; a 512x512 export of `scripts/icon-1024.png` looks sharper. |
| Gallery | `docs/panel.jpg`, `docs/agent-build.jpg`, `docs/approval.jpg`, `docs/done-ping.jpg`, plus the demo GIF below |

### First version

| Field | Value |
| --- | --- |
| Version number | `0.5.0` |
| Version title | T3 Craft 0.5.0 |
| Release channel | Beta (it relies on T3's undocumented client protocol) |
| Loaders | Fabric |
| Game versions | 26.3 |
| Dependencies | Fabric API: required |
| File | `build/libs/t3craft-0.5.0.jar` (from `./gradlew build`, or the `t3craft-jar` artifact of the tag workflow) |
| Changelog | Summarize the commits since the last release (see `git log`) |

## Description (Markdown body)

```markdown
**T3 Craft puts [T3 Code](https://t3.codes) inside Minecraft.** Send a prompt to your coding agent, go back to mining, and get one ping when it finishes, needs an approval, or has a question. Approve commands and answer questions without leaving the game. Agents can even see and build in your world.

![The T3 panel in Minecraft](https://raw.githubusercontent.com/maxwellyoung/t3craft/main/docs/panel.jpg)

> You need T3 Code running on a machine you control (your laptop, a home server). T3 Craft talks to it the same way the T3 mobile app does. Verified against **T3 Code nightly 0.0.46-nightly.20261004.2644**; legacy (protocol 1) servers are also supported.

## Features

- **The panel.** Press <kbd>`</kbd> for your threads and the conversation, with Markdown rendered. Enter sends and drops you back into the game; Shift+Enter keeps the panel open. Start new threads and switch models from the header.
- **Pings, not spam.** The corner shows `Thread · Working 1m 12s`. When a thread finishes, fails, or needs you, you get one toast and a note-block sound. Press <kbd>`</kbd> within a minute to jump to it.
- **Approvals and questions in game.** <kbd>Y</kbd> / <kbd>N</kbd> to approve or deny a command. Answer an agent's question with <kbd>1</kbd>-<kbd>9</kbd> or type your own.
- **Every machine at once.** Pair your laptop and your home server; all their threads share one sidebar with a machine filter.
- **Villagers for threads.** `/t3 village` turns recent threads into villagers whose name tags and particles show what they are doing. Right-click one that needs you to open its thread.
- **Report books.** When a thread you started from the game finishes, you get a written book with the agent's reply and the files it changed.
- **Shared server village.** Install on a Fabric server too and `/t3village` shows a team's threads as real villagers to everyone.
- **Let agents use Minecraft.** A loopback-only MCP server lets agents on your machine read the world and, only after `/t3 agent-build on`, run commands as you. Every command shows in your chat.

## Setup

1. Install Fabric Loader 0.19.5+ for Minecraft 26.3, plus Fabric API. Drop T3 Craft into `mods/`.
2. In T3 Code, open **Settings → Connections** and create a pairing link. If Minecraft runs on another machine, turn on network access first. On a headless server, run `t3 pair`.
3. In game: `/t3 pair <link>`. Press <kbd>`</kbd>.

Pairing asks only for `orchestration:read orchestration:operate`. The token lasts 30 days and is stored in `config/t3craft.json` with owner-only permissions. Revoke "Minecraft" in T3 under **Settings → Connections** to remove it.

## Network and privacy

- Connects only to the T3 environments you pair, over HTTP(S) and WebSocket. Prompts never go through server chat.
- The MCP server listens on `127.0.0.1:25590` only and refuses requests from web pages. Running commands is off until you turn it on.
- On a shared server, everyone can read thread titles and pending commands on the village name tags. Only pair a server you share with people you would show them to.

## Compatibility

| | Version |
| --- | --- |
| Minecraft | 26.3 (Fabric) |
| Java | 25+ |
| T3 Code | 0.0.46 nightly (protocol 2) and legacy protocol 1 servers |

T3's client protocol is not a public API. When T3 changes it, T3 Craft tells you in game which side to update rather than failing quietly.

---

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT. T3 Craft is an independent community project, not affiliated with or endorsed by T3 Tools Inc.
```

## Demo GIF shot list

Target: 15 to 25 seconds, 960x540 or larger, under 10 MB (Modrinth gallery limit is generous, but GitHub READMEs render big GIFs slowly). Record in a bright, readable world (a plains village works). Hide the F3 overlay. Use a short, visual prompt so the result fits the clip.

1. **Cold open (2 s).** Player standing in the world, corner HUD idle. Press <kbd>`</kbd>: the panel slides open with several threads from two machines in the sidebar.
2. **Prompt (3 s).** Click a thread, type `Build a small oak cabin 5 blocks in front of me`, press Enter. Panel closes; the corner shows `Thread · Working 0m 03s`.
3. **Keep playing (3 s).** Walk away, mine a block or two while the timer ticks. This is the point of the mod; don't cut it.
4. **Approval (3 s).** Toast plus note-block: `Needs you: minecraft_run_command /fill ...`. Press <kbd>Y</kbd>.
5. **Agent builds (4 s).** Blocks appear in front of the player; each command the agent ran is logged in chat.
6. **Done ping (2 s).** Single "Done" toast. Press <kbd>`</kbd> to open the reply rendered in the panel.
7. **Villagers (3 s, optional).** `/t3 village`: villagers with status name tags appear; one sparkles as done. Right-click one to open its thread.
8. **End card (1 s).** Freeze on the built cabin with the panel closed.

Setup before recording: `/t3 agent-build on`, add the MCP server to the agent (`claude mcp add --transport http -s user minecraft http://127.0.0.1:25590/mcp`), start a fresh thread so the agent sees the tools, and set the thread to approval-required so step 4 happens.

## Before publishing

- Record the GIF and in-game screenshots against the build being released.
- Change README install step 1 to point at the Modrinth page once it exists.
- Keep the T3 range here, in `README.md`, in `T3Api.SUPPORTED_T3`, and in `fabric.mod.json` in sync.

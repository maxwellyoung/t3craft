# Changelog

All notable changes to T3 Craft. Versions follow the jar version in `gradle.properties`.

## 0.2.0 (unreleased): first public release

Supports Minecraft Java 26.3 (Fabric Loader 0.19.5+, Fabric API 0.161.0+26.3, Java 25) and T3 Code 0.0.43 to 0.0.46 nightly.

### Added

- **T3 Code 0.0.46 nightly support (orchestration protocol 2).** T3 replaced its orchestration protocol in the 0.0.46 nightlies: threads now report runs and runtime requests instead of turns and activities, commands go over the RPC socket instead of `POST /api/orchestration/dispatch`, and the orchestration HTTP routes require an `x-t3-orchestration-protocol` header. T3 Craft now asks each environment which protocol it speaks and uses the matching one, so stable (0.0.43 to 0.0.45) and nightly machines can be paired at the same time.
- **Version check.** Pairing and the panel name the environment's T3 version and say whether to update T3 Code or T3 Craft when the environment speaks a protocol this build doesn't support.
- Unit tests for the protocol-2 mapping (`./gradlew test`, run in CI).
- A tag-triggered GitHub Actions workflow that builds the release jar as a workflow artifact. It does not publish anywhere.

### Fixed

- On protocol 2, the thread list no longer empties right after connecting. T3 follows the shell snapshot with project-metadata frames whose thread lists are intentionally empty, and those were being applied as full snapshots.
- Stop now targets the running run on protocol 2 (it needs the run id, not just the thread id).

### Features carried over from the 0.1.x prototypes

- In-game T3 panel (`` ` ``): thread list across every paired machine, Markdown conversation view, per-thread drafts, model picker, new threads.
- Corner status, toasts, and note-block pings when a watched thread finishes, fails, needs an approval, or asks a question. Approve with Y/N; answer questions with 1-9 or free text.
- `/t3` client commands, `/t3 village` client-side villagers, and report books for finished threads.
- Shared server village (`/t3village`) on dedicated servers.
- Loopback MCP server (`http://127.0.0.1:25590/mcp`) so agents can see the world and, after `/t3 agent-build on`, run commands.

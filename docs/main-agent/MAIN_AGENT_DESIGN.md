# MainAgent design — one selectable main, the rest as subs

Goal: the user picks the main agent among Codex, Antigravity, and Claude.
The other two are callable as subs inside the same chat.

## 1. MainDriver interface (the abstraction all three mains hide behind)

Every main driver must implement the same seven operations, no matter
which CLI it wraps:

1. `startSession` — open a session, optionally seeded with a transcript.
2. `sendMessage` — send a user message, streaming events back.
3. `interrupt` — stop the current turn without killing the session.
4. `resumeSession` — reattach to a previous session by cursor.
5. `listModels` — models and effort/thinking options this CLI offers.
6. `getUsage` — usage/limits in this CLI's own terms (optional; may be
   absent for a CLI that does not report it).
7. `close` — shut the driver process down cleanly.

Event kinds every driver must emit: `textDelta`, `toolCall`,
`approvalRequest`, `status`, `final`, `error`.

Per-CLI notes (verified from `rooms/provider-runtime.py`):

- Codex: app-server JSON-RPC over stdio (`app-server --listen stdio://`),
  threads and turns. This is the existing Engine path; step one is to
  extract it behind the interface without changing behavior.
- Claude: CLI with streaming output, `--resume`, strict MCP config
  (`--strict-mcp-config --mcp-config /workspace/mcp.json`), tools gated
  to `mcp__mobile__*` plus native auto-review on some models.
- Antigravity: CLI with `--agent mobile-room`, tool policies in
  `settings.json` (default-deny for command/read/write, allow
  `mcp(mobile/*)` when tools are on).

## 2. Transcript ownership (the biggest structural change)

Today the conversation history lives inside the Codex thread. For a
switchable main, history must live in the app as a neutral transcript:
an ordered list of user messages, assistant messages, tool calls, tool
results, and approval decisions.

- Switching main between turns = close the old driver session, open the
  new driver seeded with the transcript (verbatim, or summarized when it
  overflows the new CLI's context).
- Switching main mid-turn is out of scope for v1; switch only between
  turns.
- Each main keeps its own session cursor so returning to a previous main
  can resume instead of replaying everything.

## 3. Subs (reuse, don't rebuild)

Subs are the existing rooms guest path, unchanged in shape: the Engine
sends a prompt plus context, the guest CLI answers (with MCP tools under
approval), turns take order, project changes take turns. The only change
is that the sub set becomes "the two mains you did not pick", and each
sub reuses that CLI's driver in guest mode.

## 4. Capability matrix (what will NOT be identical per main)

| Feature | Codex main | Claude main | Antigravity main |
| --- | --- | --- | --- |
| Voice Live (realtime voices) | yes (25 voices) | no — device STT/TTS fallback | no — device STT/TTS fallback |
| Model/effort picker | Codex models + effort | Claude models + thinking | Gemini models + thinking |
| Approval policy | Codex approvals | native auto-review (some models) | request-review |
| MCP config | app-server config | `--mcp-config` JSON | `settings.json` / agents MCP config |
| Account needed | ChatGPT + Codex access | Claude account | Google / Antigravity account |
| Usage/limits display | Codex charts | Claude usage report | Antigravity limits |

## 5. Build order

1. Fork + branch `main-agent` (done: fork `woniri/mobile-codex`).
2. Extract the Codex app-server client behind `MainDriver` with zero
   behavior change; prove it by running the app unchanged.
3. Promote the Claude guest driver to host-capable; first milestone is
   plain chat with no tools, proving the abstraction.
4. Add the app-owned transcript store and per-main session cursors.
5. Add the main-switch UI (per chat, between turns).
6. Promote the Antigravity driver the same way.
7. Wire subs = the two non-main drivers through the existing rooms path.
8. Per-main approvals, model pickers, and usage displays.

## 6. License

Upstream is GPL-3.0. The GitHub fork is public (forks of public repos
cannot be private). Any distributed build of the modified app must stay
GPL-3.0 with source available. Personal, undistributed use is unaffected.

## Status (2026-11-02, JVM-tested core)

Implemented in `app/src/main/java/dev/mobilecodex/app/main/`:

- `Transcript.java` — app-owned neutral transcript (Base64 line format,
  no JSON dependency, no Android SDK needed).
- `MainAgentSession.java` — transcript plus the current main;
  `setMain` between turns keeps history and seeds the new main.
- `ClaudeMainDriver.java` — host-capable Claude CLI driver. One process
  per turn, `--resume` for multi-turn, `--append-system-prompt` seeding
  after a main switch, plain chat only (no tools yet).
- `MainDriverTest.java` + `docs/main-agent/tests/fake-claude.py` — JVM
  test harness with a fake CLI emitting the stream-json subset.

Test result: 9/9 PASS (plain chat including escaped quotes and newlines
in assistant text, main switch with transcript seeding, transcript
serialize/deserialize round-trip). Run with plain javac/java, no Android
SDK. Two real parser bugs were found and fixed by these tests
(whitespace-tolerant text-block parsing, false key matches on values).

Not yet tested: real `claude`/`agy` binaries (no CLIs or accounts here),
and the Android app build/run itself (needs Android SDK + a device).
Those are the next steps, not claimed here.

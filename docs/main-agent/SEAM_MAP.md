# Seam map — where Codex is hardwired in mobile-codex

Source: upstream `nokryong/mobile-codex` main, analyzed 2026-11-02.
Line numbers are from that snapshot; the fork may drift.

## Host path (the part that must become switchable)

- `app/src/main/java/dev/mobilecodex/app/Engine.java` (~2847 lines)
  - Owns one local app-server process, outside Activity/WebView lifecycle.
  - Spawns the Codex CLI as an app-server:
    `new ProcessBuilder(binary, "app-server", "--listen", "stdio://")`
  - Owns `CodexHome` (`CODEX_HOME` for the running app-server), account
    profiles, and the thread mapping (`localIdForCodexThread`).
  - Turn ids are scoped to their server thread; the UI may move to another
    conversation while an earlier thread continues in the same app-server.
  - Dispatches invited-agent turns via `agentRequest("agents.run", ...)`
    with a `provider` field.
  - Stops "Codex and the invited-agent runner" together.
  - UI-facing model picker, reasoning effort, and approval flows are all
    shaped by the Codex app-server protocol.
- `app/src/main/java/dev/mobilecodex/app/CodexHome.java` (~88 lines)
  - Locates/prepares the Codex home directory used by the app-server.
- `app/src/main/assets/web/app.js` (~200 KB)
  - Chat UI, thread list, model picker, mentions. Talks to the Engine,
    not to any agent directly.

## Guest path (the reusable pattern for subs)

- `app/src/main/java/dev/mobilecodex/app/AgentBridge.java` (~98 lines)
  - "App-owned Node runner for invited Claude/Antigravity agents."
  - Copies `rooms/*.cjs` + `rooms/*.py` from assets to
    `filesDir/rooms/runtime`, spawns `node service.cjs <root>`, and speaks
    JSON-RPC (`RpcClient`) with methods: `agents.run`,
    `agents.providers.check`, `agents.provider.install`,
    `agents.provider.login`, `agents.stop`, `agents.stopAll`,
    `agents.shutdown`.
- `app/src/main/assets/rooms/service.cjs`
  - "Runs invited Claude/Antigravity turns for the Engine. The Engine owns
    the conversation, its order and its history; this process only drives
    the CLIs." Guests receive a prompt and return text; they do not own
    the conversation.
- `app/src/main/assets/rooms/providers.cjs`
  - `CliProvider` class. The provider set is hardcoded:
    `providers = { claude: ..., agy: ... }` (agy = Antigravity/Gemini).
    No other provider exists anywhere in the rooms system.
- `app/src/main/assets/rooms/provider-runtime.py`
  - Builds the actual CLI command lines per provider, including MCP
    wiring (`/workspace/mcp.json`, `mcp__mobile__*` tools for Claude;
    `settings.json` tool policies for Antigravity) and install/login flows
    (`claude auth login`, Antigravity CLI install script).
- `app/src/main/assets/rooms/mobile-mcp.py`
  - The MCP tool bridge guests use for project and phone operations,
    under the Engine's per-turn approval.

## The seam, in one sentence

`Engine <-> codex app-server` is the hardwired host path; `AgentBridge <->
service.cjs <-> guest CLIs` is the reusable guest path. Making the main
switchable means building a host-capable driver per CLI (promoting the
guest drivers to host duty) and moving conversation-history ownership from
the Codex thread into the app.

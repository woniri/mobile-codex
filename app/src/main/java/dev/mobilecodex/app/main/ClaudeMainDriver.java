package dev.mobilecodex.app.main;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Host-capable driver for the Claude Code CLI, implementing MainDriver.
 *
 * <p>Argument shape mirrors the guest path in
 * {@code rooms/provider-runtime.py}: {@code claude -p --input-format
 * stream-json --output-format stream-json --verbose
 * --include-partial-messages --tools '' --strict-mcp-config --mcp-config
 * &lt;empty&gt; --setting-sources '' --settings '{"disableAllHooks":true}'
 * --permission-mode dontAsk}. Differences from the guest path, all
 * deliberate for host duty:
 *
 * <ul>
 *   <li>Each turn is one CLI process invocation. {@code claude -p} is
 *       one-shot, so multi-turn works via {@code --resume &lt;sessionId&gt;}
 *       taken from the previous turn's init/result events, exactly like
 *       the guest path does.</li>
 *   <li>Plain-chat v1: no tools. The MCP config is an empty server list,
 *       so the CLI cannot call tools; tool-call support is a later step.</li>
 *   <li>Cross-main seeding: when a session starts with a non-empty
 *       transcript (i.e. after a main switch), the transcript is passed
 *       as {@code --append-system-prompt} on the first turn only. Long
 *       transcripts will need summarization before this; that is future
 *       work, not silently handled here.</li>
 * </ul>
 *
 * <p>Parsed stream-json subset (v1): system init (session id), assistant
 * text blocks (deltas), result success/error (final text + session id).
 * Thinking blocks and tool blocks are ignored for now; plain chat has
 * neither.
 *
 * <p>JSON handling is hand-rolled and dependency-free so this compiles
 * without the Android SDK. The string extractor is escape-aware and
 * skips false key matches (e.g. the value {@code "result"} of
 * {@code "type"} must not match a search for the key {@code "result"}).
 */
public final class ClaudeMainDriver implements MainDriver {

    private final String binary;
    private final File workDir;
    private final String model;
    private final String effort;

    private Listener listener;
    private String cursor = "";
    private String seed = "";
    private boolean seedPending = false;
    private volatile Process current;
    private volatile boolean closed;

    public ClaudeMainDriver(String binary, File workDir, String model, String effort) {
        this.binary = binary;
        this.workDir = workDir;
        this.model = model;
        this.effort = effort;
    }

    @Override
    public synchronized void startSession(String transcript, Listener listener) {
        this.listener = listener;
        if (transcript != null && !transcript.isEmpty()) {
            seed = transcript;
            seedPending = true;
        } else {
            seedPending = false;
        }
    }

    @Override
    public synchronized void resumeSession(String cursor, Listener listener) {
        this.listener = listener;
        this.cursor = cursor == null ? "" : cursor;
        this.seedPending = false;
    }

    @Override
    public List<ModelInfo> listModels() {
        // Model discovery via the CLI control-request channel exists in the
        // guest path; wiring it here is a later step. Empty is honest.
        return Collections.emptyList();
    }

    @Override
    public synchronized void interrupt() {
        Process p = current;
        if (p != null && p.isAlive()) {
            p.destroy();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        interrupt();
    }

    private File emptyMcpConfig() throws IOException {
        File config = new File(workDir, "mcp-empty.json");
        if (!config.exists()) {
            try (FileWriter writer = new FileWriter(config)) {
                writer.write("{\"mcpServers\":{}}");
            }
        }
        return config;
    }

    @Override
    public void sendMessage(String text) throws Exception {
        final Listener active;
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("driver closed");
            }
            if (listener == null) {
                throw new IllegalStateException("no session started");
            }
            active = listener;
        }

        List<String> argv = new ArrayList<>();
        argv.add(binary);
        argv.add("-p");
        argv.add("--input-format");
        argv.add("stream-json");
        argv.add("--output-format");
        argv.add("stream-json");
        argv.add("--verbose");
        argv.add("--include-partial-messages");
        argv.add("--tools");
        argv.add("");
        argv.add("--strict-mcp-config");
        argv.add("--mcp-config");
        argv.add(emptyMcpConfig().getAbsolutePath());
        argv.add("--setting-sources");
        argv.add("");
        argv.add("--settings");
        argv.add("{\"disableAllHooks\":true}");
        argv.add("--permission-mode");
        argv.add("dontAsk");
        synchronized (this) {
            if (seedPending) {
                argv.add("--append-system-prompt");
                argv.add(seed);
                seedPending = false;
            }
            if (!cursor.isEmpty()) {
                argv.add("--resume");
                argv.add(cursor);
            }
        }
        if (model != null && !model.isEmpty()) {
            argv.add("--model");
            argv.add(model);
        }
        if (effort != null && !effort.isEmpty()) {
            argv.add("--effort");
            argv.add(effort);
        }

        ProcessBuilder builder = new ProcessBuilder(argv);
        if (workDir != null) {
            builder.directory(workDir);
        }
        builder.redirectErrorStream(false);
        Process process = builder.start();
        synchronized (this) {
            current = process;
        }
        // Drain stderr so a chatty CLI cannot block on a full pipe.
        Thread drain = new Thread(() -> {
            try {
                byte[] buffer = new byte[4096];
                while (process.getErrorStream().read(buffer) != -1) {
                    // Discard.
                }
            } catch (IOException ignored) {
                // Stream closed with the process; nothing to do.
            }
        });
        drain.setDaemon(true);
        drain.start();

        // One stream-json user message, then EOF.
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.write("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\""
                    + jsonEscape(text) + "\"}]}}\n");
            writer.flush();
        }

        StringBuilder fullText = new StringBuilder();
        boolean sawFinal = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String type = extractString(line, "type");
                if (type == null) {
                    continue;
                }
                if (type.equals("system")) {
                    String sessionId = extractString(line, "session_id");
                    if (sessionId != null) {
                        synchronized (this) {
                            cursor = sessionId;
                        }
                    }
                    active.onEvent(Event.status("started"));
                } else if (type.equals("assistant")) {
                    String delta = extractAssistantText(line);
                    if (delta != null && !delta.isEmpty()) {
                        fullText.append(delta);
                        active.onEvent(Event.text(delta));
                    }
                } else if (type.equals("result")) {
                    String sessionId = extractString(line, "session_id");
                    if (sessionId != null) {
                        synchronized (this) {
                            cursor = sessionId;
                        }
                    }
                    String subtype = extractString(line, "subtype");
                    if ("success".equals(subtype)) {
                        String result = extractString(line, "result");
                        active.onEvent(Event.finalEvent(
                                result != null ? result : fullText.toString(), cursor));
                    } else {
                        active.onEvent(Event.error("result subtype: " + subtype));
                    }
                    sawFinal = true;
                }
            }
        }
        int exitCode = process.waitFor();
        synchronized (this) {
            if (current == process) {
                current = null;
            }
        }
        if (!sawFinal) {
            if (exitCode != 0) {
                active.onEvent(Event.error("CLI exited with code " + exitCode));
            } else {
                active.onEvent(Event.finalEvent(fullText.toString(), cursor));
            }
        }
    }

    /**
     * Extract the text of the first text content block in an assistant line.
     * Whitespace-tolerant: real stream-json is compact, but valid JSON may
     * carry spaces after colons, and the parser must not depend on that.
     */
    static String extractAssistantText(String line) {
        String typePattern = "\"type\"";
        int from = 0;
        while (true) {
            int at = line.indexOf(typePattern, from);
            if (at < 0) {
                return null;
            }
            String value = extractStringFrom(line, at);
            if ("text".equals(value)) {
                // The value "text" itself matches the pattern first; skip
                // past it until a real "text" key (followed by ': "') parses.
                int textFrom = at + 1;
                while (true) {
                    int textKey = line.indexOf("\"text\"", textFrom);
                    if (textKey < 0) {
                        return null;
                    }
                    String text = extractStringFrom(line, textKey);
                    if (text != null) {
                        return text;
                    }
                    textFrom = textKey + 1;
                }
            }
            from = at + 1;
        }
    }

    /** Extract the string value of a top-level key, skipping false matches. */
    static String extractString(String line, String key) {
        String pattern = "\"" + key + "\"";
        int from = 0;
        while (true) {
            int at = line.indexOf(pattern, from);
            if (at < 0) {
                return null;
            }
            String value = extractStringFrom(line, at);
            if (value != null) {
                return value;
            }
            from = at + 1;
        }
    }

    /**
     * Parse the string value whose key starts at {@code keyAt}, or null if
     * what follows is not {@code : "string"}. Escape-aware.
     */
    private static String extractStringFrom(String line, int keyAt) {
        int i = line.indexOf('"', keyAt + 1);
        if (i < 0) {
            return null;
        }
        // keyAt points at the opening quote of the key; find its closing quote.
        int keyEnd = line.indexOf('"', keyAt + 1);
        if (keyEnd < 0) {
            return null;
        }
        int j = keyEnd + 1;
        while (j < line.length() && Character.isWhitespace(line.charAt(j))) {
            j++;
        }
        if (j >= line.length() || line.charAt(j) != ':') {
            return null;
        }
        j++;
        while (j < line.length() && Character.isWhitespace(line.charAt(j))) {
            j++;
        }
        if (j >= line.length() || line.charAt(j) != '"') {
            return null;
        }
        StringBuilder out = new StringBuilder();
        j++;
        while (j < line.length()) {
            char c = line.charAt(j);
            if (c == '\\' && j + 1 < line.length()) {
                char next = line.charAt(j + 1);
                switch (next) {
                    case 'n':
                        out.append('\n');
                        j += 2;
                        break;
                    case 'r':
                        out.append('\r');
                        j += 2;
                        break;
                    case 't':
                        out.append('\t');
                        j += 2;
                        break;
                    case 'b':
                        out.append('\b');
                        j += 2;
                        break;
                    case 'f':
                        out.append('\f');
                        j += 2;
                        break;
                    case '/':
                        out.append('/');
                        j += 2;
                        break;
                    case '\\':
                        out.append('\\');
                        j += 2;
                        break;
                    case '"':
                        out.append('"');
                        j += 2;
                        break;
                    case 'u':
                        if (j + 5 < line.length()) {
                            try {
                                out.append((char) Integer.parseInt(line.substring(j + 2, j + 6), 16));
                            } catch (NumberFormatException ignored) {
                                // Keep going; a bad escape must not kill the turn.
                            }
                            j += 6;
                        } else {
                            j += 2;
                        }
                        break;
                    default:
                        out.append(next);
                        j += 2;
                        break;
                }
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
                j++;
            }
        }
        return null;
    }

    static String jsonEscape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.toString();
    }
}

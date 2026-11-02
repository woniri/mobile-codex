package dev.mobilecodex.app.main;

import java.util.List;
import java.util.Map;

/**
 * Skeleton, design stage only — not yet wired into Engine or AgentBridge.
 *
 * <p>One interface every main-agent driver must implement, no matter which
 * CLI it wraps (Codex app-server, Claude CLI, Antigravity CLI). The goal is
 * that the Engine talks to this interface only, never to a specific CLI's
 * protocol, so the user can pick any of the three as the main and the other
 * two run as subs through the existing rooms guest path.
 *
 * <p>Dependency-free (java.util only) so it compiles without the Android
 * SDK; see the build note in MAIN_AGENT_DESIGN.md.
 */
public interface MainDriver {

    /** Event kinds every driver must emit while streaming a turn. */
    enum EventKind {
        TEXT_DELTA,
        TOOL_CALL,
        APPROVAL_REQUEST,
        STATUS,
        FINAL,
        ERROR
    }

    /** One streamed event. Only the fields relevant to the kind are set. */
    final class Event {
        public final EventKind kind;
        public final String text;
        public final String toolName;
        public final Map<String, String> toolArgs;
        public final String approvalTitle;
        public final String approvalDetail;
        public final String status;
        public final String sessionCursor;

        public Event(EventKind kind, String text, String toolName,
                     Map<String, String> toolArgs, String approvalTitle,
                     String approvalDetail, String status, String sessionCursor) {
            this.kind = kind;
            this.text = text;
            this.toolName = toolName;
            this.toolArgs = toolArgs;
            this.approvalTitle = approvalTitle;
            this.approvalDetail = approvalDetail;
            this.status = status;
            this.sessionCursor = sessionCursor;
        }

        public static Event text(String delta) {
            return new Event(EventKind.TEXT_DELTA, delta, null, null, null, null, null, null);
        }

        public static Event status(String status) {
            return new Event(EventKind.STATUS, null, null, null, null, null, status, null);
        }

        public static Event finalEvent(String text, String sessionCursor) {
            return new Event(EventKind.FINAL, text, null, null, null, null, null, sessionCursor);
        }

        public static Event error(String message) {
            return new Event(EventKind.ERROR, message, null, null, null, null, null, null);
        }
    }

    /** Receives streamed events for one turn. */
    interface Listener {
        void onEvent(Event event);

        /** Called when the driver needs an approval decision; return true to allow. */
        boolean onApproval(String title, String detail);
    }

    /** A model this driver can run, with its effort/thinking options. */
    final class ModelInfo {
        public final String id;
        public final List<String> efforts;

        public ModelInfo(String id, List<String> efforts) {
            this.id = id;
            this.efforts = efforts;
        }
    }

    /** Open a session, optionally seeded with the app-owned transcript. */
    void startSession(String transcriptJson, Listener listener) throws Exception;

    /** Send a user message; events stream to the session listener. */
    void sendMessage(String text) throws Exception;

    /** Stop the current turn without killing the session. */
    void interrupt() throws Exception;

    /** Reattach to a previous session by cursor. */
    void resumeSession(String cursor, Listener listener) throws Exception;

    /** Models and effort options this CLI offers. */
    List<ModelInfo> listModels() throws Exception;

    /** Shut the driver process down cleanly. */
    void close() throws Exception;
}

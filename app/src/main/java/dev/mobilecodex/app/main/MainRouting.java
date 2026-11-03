package dev.mobilecodex.app.main;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure routing rules for the selectable main, dependency-free so they run
 * under plain javac/java without the Android SDK.
 *
 * <p>On Android the main turn itself runs through the existing rooms guest
 * path (Engine -> AgentBridge -> agents.run), not through
 * {@link ClaudeMainDriver}'s ProcessBuilder: the guest CLIs live inside the
 * app's Linux/devtools environment and are only reachable through that
 * chain (proot launch, per-provider HOME). This class only decides WHO
 * answers a turn; it never spawns a CLI.
 */
public final class MainRouting {

    private MainRouting() {}

    /** Canonical main ids: "codex", "claude", "gemini" (Antigravity). */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "codex":
            case "코덱스":
                return "codex";
            case "claude":
            case "클로드":
                return "claude";
            case "gemini":
            case "agy":
            case "antigravity":
            case "제미나이":
                return "gemini";
            default:
                return null;
        }
    }

    /**
     * Ordered speakers for a turn under the given main, or null when the
     * caller should keep the existing dispatch (Codex main, or an explicit
     * @codex mention that still leads with Codex).
     *
     * <p>Otherwise the main answers first, then the other enabled guests in
     * invitation order. Codex is not queued: it only sees the message if a
     * later turn calls it.
     */
    public static List<String> selectTargets(String mainId, List<String> mentioned,
            List<String> enabledGuestIds) {
        if (mainId == null || mainId.equals("codex")) {
            return null;
        }
        if (mentioned != null && mentioned.contains("codex")) {
            return null;
        }
        List<String> out = new ArrayList<>();
        out.add(mainId);
        if (enabledGuestIds != null) {
            for (String id : enabledGuestIds) {
                if (!id.equals(mainId) && !out.contains(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }
}

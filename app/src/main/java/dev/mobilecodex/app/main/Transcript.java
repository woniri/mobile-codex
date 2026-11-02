package dev.mobilecodex.app.main;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * App-owned neutral transcript. The conversation history lives here, not
 * inside any single agent's session, so the main can be switched between
 * turns without losing history.
 *
 * <p>Serialization is deliberately JSON-free (one line per entry,
 * {@code role|mainId|base64(text)}) so this class compiles and runs without
 * the Android SDK or any JSON library. Base64 text never contains the
 * {@code |} delimiter, so splitting is safe.
 */
public final class Transcript {

    /** One turn. Role is "user" or "assistant". mainId is "" for user turns. */
    public static final class Entry {
        public final String role;
        public final String mainId;
        public final String text;

        public Entry(String role, String mainId, String text) {
            this.role = role;
            this.mainId = mainId == null ? "" : mainId;
            this.text = text == null ? "" : text;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public void addUser(String text) {
        entries.add(new Entry("user", "", text));
    }

    public void addAssistant(String mainId, String text) {
        entries.add(new Entry("assistant", mainId, text));
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public String serialize() {
        StringBuilder out = new StringBuilder();
        for (Entry e : entries) {
            out.append(e.role).append('|').append(e.mainId).append('|')
                    .append(Base64.getEncoder().encodeToString(e.text.getBytes(StandardCharsets.UTF_8)))
                    .append('\n');
        }
        return out.toString();
    }

    public static Transcript deserialize(String serialized) {
        Transcript transcript = new Transcript();
        if (serialized == null || serialized.isEmpty()) {
            return transcript;
        }
        for (String line : serialized.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\|", 3);
            if (parts.length != 3) {
                continue;
            }
            String text = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
            transcript.entries.add(new Entry(parts[0], parts[1], text));
        }
        return transcript;
    }

    /** Human-readable context handed to a new main when switching. */
    public String toContextText() {
        StringBuilder out = new StringBuilder();
        for (Entry e : entries) {
            if (e.role.equals("user")) {
                out.append("user: ").append(e.text).append('\n');
            } else {
                out.append("assistant (").append(e.mainId).append("): ").append(e.text).append('\n');
            }
        }
        return out.toString();
    }
}

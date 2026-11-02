package dev.mobilecodex.app.main;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * JVM test harness for the main-agent core. Not shipped with the app and
 * not an Android test: run with plain javac/java against a fake CLI.
 *
 * <p>Usage:
 * {@code java -cp <classes> dev.mobilecodex.app.main.MainDriverTest
 * <fake-claude-path> <work-dir>}
 *
 * <p>Tests:
 * <ol>
 *   <li>Plain chat through ClaudeMainDriver against the fake CLI, including
 *       escaped quotes and newlines in the assistant text.</li>
 *   <li>Main switch: setMain to a second driver keeps the transcript and
 *       seeds the new main with it via --append-system-prompt.</li>
 *   <li>Transcript serialize/deserialize round-trip.</li>
 * </ol>
 */
public final class MainDriverTest {

    private static int failures = 0;

    private static void check(String name, boolean condition) {
        System.out.println((condition ? "PASS " : "FAIL ") + name);
        if (!condition) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        File fakeClaude = new File(args[0]);
        File workDir = new File(args[1]);
        workDir.mkdirs();
        File argvLog = new File(fakeClaude.getParentFile(), "argv.log");
        if (argvLog.exists()) {
            argvLog.delete();
        }

        final List<MainDriver.Event> events = new ArrayList<>();
        MainAgentSession.AppListener listener = new MainAgentSession.AppListener() {
            @Override
            public void onEvent(MainDriver.Event event) {
                events.add(event);
            }

            @Override
            public boolean onApproval(String title, String detail) {
                return false;
            }
        };

        // Test 1: plain chat.
        MainAgentSession session = new MainAgentSession(listener);
        ClaudeMainDriver claude = new ClaudeMainDriver(
                fakeClaude.getAbsolutePath(), workDir, null, null);
        session.setMain(claude, "claude");
        session.sendMessage("hello");

        String expected = "Hi! I got \"quoted\" text.\nSecond line.";
        MainDriver.Event finalEvent = null;
        boolean sawDelta = false;
        for (MainDriver.Event event : events) {
            if (event.kind == MainDriver.EventKind.TEXT_DELTA
                    && event.text != null && event.text.contains("quoted")) {
                sawDelta = true;
            }
            if (event.kind == MainDriver.EventKind.FINAL) {
                finalEvent = event;
            }
        }
        check("T1 delta received with quoted text", sawDelta);
        check("T1 final text matches (escapes parsed)", finalEvent != null && expected.equals(finalEvent.text));
        check("T1 transcript has user + assistant entries",
                session.transcript().entries().size() == 2
                        && session.transcript().entries().get(0).role.equals("user")
                        && session.transcript().entries().get(0).text.equals("hello")
                        && session.transcript().entries().get(1).role.equals("assistant")
                        && session.transcript().entries().get(1).mainId.equals("claude")
                        && expected.equals(session.transcript().entries().get(1).text));

        List<String> argvLines = Files.readAllLines(argvLog.toPath(), StandardCharsets.UTF_8);
        check("T1 first invocation has no seed flag", !argvLines.isEmpty()
                && !argvLines.get(0).contains("--append-system-prompt"));

        // Test 2: main switch keeps the transcript and seeds the new main.
        events.clear();
        ClaudeMainDriver second = new ClaudeMainDriver(
                fakeClaude.getAbsolutePath(), workDir, null, null);
        session.setMain(second, "codex");
        session.sendMessage("second");

        argvLines = Files.readAllLines(argvLog.toPath(), StandardCharsets.UTF_8);
        String lastArgv = argvLines.get(argvLines.size() - 1);
        check("T2 switch seeds new main via --append-system-prompt",
                lastArgv.contains("--append-system-prompt") && lastArgv.contains("hello"));
        check("T2 transcript keeps history across switch",
                session.transcript().entries().size() == 4
                        && session.transcript().entries().get(2).text.equals("second")
                        && session.transcript().entries().get(3).mainId.equals("codex"));
        check("T2 current main id updated", session.currentMainId().equals("codex"));

        // Test 3: transcript round-trip.
        Transcript restored = Transcript.deserialize(session.transcript().serialize());
        check("T3 transcript serialize/deserialize round-trip",
                restored.entries().size() == session.transcript().entries().size()
                        && restored.entries().get(1).text.equals(expected));

        session.close();
        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " TEST(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }
}

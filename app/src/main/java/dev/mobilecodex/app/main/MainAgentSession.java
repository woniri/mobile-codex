package dev.mobilecodex.app.main;

/**
 * Owns one chat: the app-owned transcript plus whichever main driver is
 * currently selected. Switching mains between turns keeps the transcript;
 * the new main is seeded with it (see ClaudeMainDriver's
 * --append-system-prompt seeding) and keeps its own session cursor.
 *
 * <p>Single-threaded use is assumed (the app's Engine serializes turns the
 * same way); methods are synchronized as a safety net, not as a
 * concurrency design.
 */
public final class MainAgentSession {

    /** App-side listener: receives forwarded driver events. */
    public interface AppListener {
        void onEvent(MainDriver.Event event);

        boolean onApproval(String title, String detail);
    }

    private final Transcript transcript = new Transcript();
    private final AppListener appListener;
    private MainDriver current;
    private String currentMainId = "";

    public MainAgentSession(AppListener appListener) {
        this.appListener = appListener;
    }

    public Transcript transcript() {
        return transcript;
    }

    public synchronized String currentMainId() {
        return currentMainId;
    }

    /**
     * Switch the main driver. The old driver is closed; the new one is
     * started with the full transcript as context. Switching mid-turn is
     * not supported: call this between turns only.
     */
    public synchronized void setMain(MainDriver driver, String mainId) throws Exception {
        if (current != null) {
            current.close();
        }
        current = driver;
        currentMainId = mainId;
        driver.startSession(transcript.toContextText(), new ForwardingListener());
    }

    /** Send a user message through the current main. */
    public synchronized void sendMessage(String text) throws Exception {
        if (current == null) {
            throw new IllegalStateException("no main selected; call setMain first");
        }
        transcript.addUser(text);
        current.sendMessage(text);
    }

    public synchronized void interrupt() throws Exception {
        if (current != null) {
            current.interrupt();
        }
    }

    public synchronized void close() throws Exception {
        if (current != null) {
            current.close();
            current = null;
        }
    }

    private final class ForwardingListener implements MainDriver.Listener {
        @Override
        public void onEvent(MainDriver.Event event) {
            if (event.kind == MainDriver.EventKind.FINAL && event.text != null) {
                transcript.addAssistant(currentMainId, event.text);
            }
            appListener.onEvent(event);
        }

        @Override
        public boolean onApproval(String title, String detail) {
            return appListener.onApproval(title, detail);
        }
    }
}

package dev.mobilecodex.app.main;

import java.util.List;

/** Plain-javac tests for MainRouting. Run: javac MainRouting.java MainRoutingTest.java && java MainRoutingTest */
public final class MainRoutingTest {

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("FAILED: " + label);
        }
        System.out.println("ok: " + label);
    }

    public static void main(String[] args) {
        check("codex".equals(MainRouting.normalize("codex")), "normalize codex");
        check("claude".equals(MainRouting.normalize("Claude")), "normalize Claude case-insensitive");
        check("gemini".equals(MainRouting.normalize("agy")), "normalize agy -> gemini");
        check("gemini".equals(MainRouting.normalize("antigravity")), "normalize antigravity -> gemini");
        check("claude".equals(MainRouting.normalize("클로드")), "normalize 클로드");
        check(MainRouting.normalize("unknown") == null, "normalize unknown -> null");
        check(MainRouting.normalize(null) == null, "normalize null -> null");

        check(MainRouting.selectTargets("codex", List.of(), List.of("claude")) == null,
                "codex main keeps existing dispatch");
        check(MainRouting.selectTargets("claude", List.of("codex"), List.of("claude", "gemini")) == null,
                "explicit @codex keeps existing dispatch");
        check(MainRouting.selectTargets("claude", List.of(), List.of("claude", "gemini"))
                .equals(List.of("claude", "gemini")), "claude main first, then guests");
        check(MainRouting.selectTargets("gemini", List.of("gemini"), List.of("claude", "gemini"))
                .equals(List.of("gemini", "claude")), "gemini main first even when mentioned");
        check(MainRouting.selectTargets("claude", List.of(), List.of())
                .equals(List.of("claude")), "claude main alone");
        System.out.println("MainRoutingTest: all passed");
    }
}

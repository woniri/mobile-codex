#!/usr/bin/env python3
"""Fake `claude` CLI for JVM tests of ClaudeMainDriver.

Emits the stream-json subset the driver parses: system init (session id),
one assistant text block, and a result. The assistant text deliberately
contains an escaped quote and a newline to prove the driver's
escape-aware parsing.

Usage: this script is invoked exactly like the real CLI. It appends one
JSON line of its argv to <script-dir>/argv.log on every invocation so
tests can assert which flags the driver passed (e.g. --append-system-prompt
after a main switch, --resume with the previous session id).
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "argv.log")


def main():
    argv = sys.argv[1:]
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(json.dumps(argv, ensure_ascii=False) + "\n")

    resume = None
    i = 0
    while i < len(argv):
        if argv[i] == "--resume" and i + 1 < len(argv):
            resume = argv[i + 1]
            i += 2
        else:
            i += 1

    # Drain stdin (the driver sends one stream-json user message).
    try:
        for _ in sys.stdin:
            pass
    except Exception:
        pass

    session_id = resume if resume else "sess-new"
    text = 'Hi! I got "quoted" text.\nSecond line.'
    print(json.dumps({"type": "system", "subtype": "init", "session_id": session_id}))
    print(json.dumps({"type": "assistant", "message": {"role": "assistant",
          "content": [{"type": "text", "text": text}]}}))
    print(json.dumps({"type": "result", "subtype": "success",
                      "session_id": session_id, "result": text}))
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())

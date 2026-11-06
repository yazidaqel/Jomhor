"""Fail if tracked source files contain raw invisible, bidi or combining characters.

Write such characters as escapes instead: invisible characters in source are a review risk.
This file stays pure ASCII on purpose (ranges are built from code points).
"""
import os
import re
import subprocess
import sys

RANGES = [(0x0300, 0x036F), (0x061C, 0x061C), (0x200B, 0x200F), (0x202A, 0x202E),
          (0x2066, 0x2069), (0x00A0, 0x00A0), (0x202F, 0x202F), (0xFEFF, 0xFEFF)]
BAD = re.compile("[" + "".join(re.escape(chr(a)) + "-" + re.escape(chr(b)) for a, b in RANGES) + "]")
PATTERNS = ["*.java", "*.kts", "*.sql", "*.ts", "*.tsx", "*.mjs", "*.dart"]

files = subprocess.run(["git", "ls-files", *PATTERNS], capture_output=True, text=True, check=True).stdout.split()
hits = []
for path in files:
    if not os.path.exists(path):  # deleted in the working tree, not yet committed
        continue
    with open(path, encoding="utf-8", errors="replace") as f:
        for number, line in enumerate(f, 1):
            if BAD.search(line):
                hits.append(path + ":" + str(number))
print("\n".join(hits) or "ok")
sys.exit(1 if hits else 0)

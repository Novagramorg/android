# Vendored: libprisma

Upstream Telegram for Android (DrKLO/Telegram 13.2.2, 21f7d109d) pins this as a git submodule:

- source: https://github.com/TelegramMessenger/libprisma.git
- commit: 94640c63c20eacdf70e37e23198d9ab53c802ebd

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. The libprisma/ sources and headers (plus LICENSE): since 13.2 jni/prisma/CMakeLists.txt builds prisma from them (with jni/prisma/libprisma/ overriding Highlight.* and SyntaxHighlighter.*) instead of linking a prebuilt libprisma.a; its grammar data ships as assets/grammars.dat.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset (every file byte-identical to the pin).

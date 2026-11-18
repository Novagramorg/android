# Vendored: quickjs

Pinned by widget-sandbox c498cefdd (its own submodule); upstream Telegram for Android (DrKLO/Telegram 13.2.2, 21f7d109d) builds it:

- source: https://github.com/TelegramMessenger/quickjs.git
- commit: ca3594dae5fd0dd6d667be74a322e8e935a2d217

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. LICENSE, the top-level headers and the four sources twidget.cmake compiles (quickjs.c, dtoa.c, libregexp.c, libunicode.c). Left out: tests/, test262, examples/, docs/, the qjs/qjsc programs, quickjs-libc and fuzzers.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset (every file byte-identical to the pin).

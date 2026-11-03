# Vendored: tgcalls

Upstream Telegram for Android (DrKLO/Telegram 13.1.1, 3de37c8cf) pins this as a git submodule:

- source: https://github.com/TelegramMessenger/tgcalls.git
- commit: 1398c1648f52666b3249111ec3c5b531946e41e4

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. The whole `tgcalls/` source directory is kept (plus LICENSE): jni/voip/CMakeLists.txt
compiles it from here. Until 12.10.6 the same 267 files lived in-tree at jni/voip/tgcalls.
Left out: tools/, macos/, docs/ and the Swift package files, which the Android build never reads.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset.

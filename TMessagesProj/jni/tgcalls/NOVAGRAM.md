# Vendored: tgcalls

Upstream Telegram for Android (DrKLO/Telegram 13.2.2, 21f7d109d) pins this as a git submodule:

- source: https://github.com/TelegramMessenger/tgcalls.git
- commit: 545f5ae599f8282e1ae3f38aaff166d179d75703

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. The whole `tgcalls/` source directory is kept (plus LICENSE): jni/voip/CMakeLists.txt compiles it from here. Left out: tools/, macos/, docs/, the Swift package and editor files, which the Android build never reads.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset (every file byte-identical to the pin).

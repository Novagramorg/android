# Vendored: tgcalls

Upstream Telegram for Android (DrKLO/Telegram 13.0.2, f32ffeec7) pins this as a git submodule:

- source: https://github.com/TelegramMessenger/tgcalls.git
- commit: ccbb57965020768c94f5b205ea08547a7e0fe49d

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. The whole `tgcalls/` source directory is kept (plus LICENSE): jni/voip/CMakeLists.txt
compiles it from here. Until 12.10.6 the same 267 files lived in-tree at jni/voip/tgcalls.
Left out: tools/, macos/, docs/ and the Swift package files, which the Android build never reads.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset.

# Vendored: tlottie

Upstream Telegram for Android (DrKLO/Telegram 12.10.6, f2908b141) pins this as a git submodule:

- source: https://github.com/dkaraush/tlottie.git
- commit: 92df98dc209bc39b1e567ec74a8c86a0af5239de

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. Only include/tlottie.h is needed: the Rust library is linked from the prebuilt jni/prebuild/lib/<abi>/libtlottie.a.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset.

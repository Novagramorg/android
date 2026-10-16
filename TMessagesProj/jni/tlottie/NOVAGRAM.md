# Vendored: tlottie

Upstream Telegram for Android (DrKLO/Telegram 13.0.2, f32ffeec7) pins this as a git submodule:

- source: https://github.com/dkaraush/tlottie.git
- commit: 31f1b542f88e7b4be9a01e749920d857535fc715

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. Only include/tlottie.h is needed: the Rust library is linked from the prebuilt jni/prebuild/lib/<abi>/libtlottie.a.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset.

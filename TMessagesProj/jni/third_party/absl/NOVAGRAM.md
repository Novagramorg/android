# Vendored: absl

Upstream Telegram for Android (DrKLO/Telegram 12.10.6, f2908b141) pins this as a git submodule:

- source: https://github.com/abseil/abseil-cpp.git
- commit: 54fac219c4ef0bc379dfffb0b8098725d77ac81b

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. Upstream's jni/CMakeLists.txt add_subdirectory()s abseil and compiles it from source (webrtc links absl::*), so the whole tree is kept, tests included — CMake checks source files at configure time. Only the .github/ and ci/ folders are left out.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset.

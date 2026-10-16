# Vendored: boost-regex

Upstream Telegram for Android (DrKLO/Telegram 13.0.2, f32ffeec7) pins this as a git submodule:

- source: https://github.com/boostorg/regex.git
- commit: 4cbcd3078e6ae10d05124379623a1bf03fcb9350

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. Only include/ is needed: jni/CMakeLists.txt compiles the Prisma JNI bridge against
these headers with BOOST_REGEX_STANDALONE (header-only, no Boost libraries). License: Boost Software License 1.0,
stated in each header.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset.

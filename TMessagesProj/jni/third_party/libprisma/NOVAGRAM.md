# Vendored: libprisma

Upstream Telegram for Android (DrKLO/Telegram 13.0.2, f32ffeec7) pins this as a git submodule:

- source: https://github.com/TelegramMessenger/libprisma.git
- commit: 31a5d6fbbef8136e4a2b3adaf7b95e2ccaa7436d

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. Only the libprisma/*.h headers (plus LICENSE) are needed: the library is linked from
the prebuilt jni/prebuild/lib/<abi>/libprisma.a and its grammar data ships as assets/grammars.dat.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset.

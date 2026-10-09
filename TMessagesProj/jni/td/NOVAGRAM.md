# Vendored: td

Upstream Telegram for Android (DrKLO/Telegram 12.10.6, f2908b141) pins this as a git submodule:

- source: https://github.com/tdlib/td.git
- commit: 022d60202e446ad1287b9fb68e687c8a0760788b

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. Only tde2e/ is needed (jni/tde2e/bridge.cpp includes e2e_api.h via the td/tde2e include paths): the library is linked from the prebuilt jni/prebuild/lib/<abi>/libtde2e.a and libtdutils.a.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset.

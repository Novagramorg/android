# Vendored: boringssl

Upstream Telegram for Android (DrKLO/Telegram 12.10.6, f2908b141) pins this as a git submodule:

- source: https://github.com/google/boringssl.git
- commit: 2b44a3701a4788e1ef866ddc7f143060a3d196c9

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. Only include/ is needed: BoringSSL itself is linked from the prebuilt jni/prebuild/lib/<abi>/libcrypto.a and libssl.a.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset.

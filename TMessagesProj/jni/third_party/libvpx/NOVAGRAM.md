# Vendored: libvpx

Upstream Telegram for Android (DrKLO/Telegram 13.2.2, 21f7d109d) pins this as a git submodule:

- source: https://github.com/webmproject/libvpx.git
- commit: 6df3ec34557879fff673706f4a1d9fbd0f3a6f0e

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. Only the public vpx/ headers are needed: libvpx is linked from the prebuilt jni/prebuild/lib/<abi>/libvpx.a.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset (every file byte-identical to the pin).

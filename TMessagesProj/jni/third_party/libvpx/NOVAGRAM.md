# Vendored: libvpx

Upstream Telegram for Android (DrKLO/Telegram 12.10.6, f2908b141) pins this as a git submodule:

- source: https://github.com/webmproject/libvpx.git
- commit: 1024874c5919305883187e2953de8fcb4c3d7fa6

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. Only the public vpx/ headers are needed: libvpx is linked from the prebuilt jni/prebuild/lib/<abi>/libvpx.a.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset.

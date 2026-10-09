# Vendored: dav1d

Upstream Telegram for Android (DrKLO/Telegram 12.10.6, f2908b141) pins this as a git submodule:

- source: https://github.com/videolan/dav1d.git
- commit: 54706fc6bc0cdecab7e9593974a4039cc038fca7

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. Only include/ is kept, because upstream's CMake lists third_party/dav1d/include as an include directory; dav1d is linked from the prebuilt jni/prebuild/lib/<abi>/libdav1d.a.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset.

# Vendored: ffmpeg

Upstream Telegram for Android (DrKLO/Telegram 12.10.6, f2908b141) pins this as a git submodule:

- source: https://github.com/FFmpeg/FFmpeg.git
- commit: 45f1910444f34b02621f9f0426ea1a538a613c41

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. Only the library headers are needed (upstream's CMake uses this directory as an include root, with the generated avconfig.h/ffversion.h under jni/prebuild/include/<abi>): FFmpeg is linked from the prebuilt jni/prebuild/lib/<abi>/libav*.a and libsw*.a.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset.

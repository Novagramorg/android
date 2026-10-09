# Vendored: wamr

Upstream Telegram for Android (DrKLO/Telegram 12.10.6, f2908b141) pins this as a git submodule:

- source: https://github.com/wasm-micro-runtime/wasm-micro-runtime.git
- commit: 25bd7eb63e828e4bd242cc9b38d260b4b31c6605

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. Only core/iwasm/include/ is needed (voip/tgcalls/v2wasm includes wasm_export.h): the runtime is linked from the prebuilt jni/prebuild/lib/<abi>/libiwasm.a.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset.

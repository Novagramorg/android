# Vendored: twidget

Upstream Telegram for Android (DrKLO/Telegram 13.2.2, 21f7d109d) pins this as a git submodule:

- source: https://github.com/TelegramMessenger/twidget.git
- commit: 4769d72c5c5a9bace3c7bd8b6308cb79bc150bab

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. core/ (TwidgetCore renderer) and litehtml/ (twidget's litehtml fork with gumbo), plus LICENSE and THIRD_PARTY_NOTICES.md: jni/twidget/twidget.cmake compiles them into libtwidget.so. Left out: apple/, platforms/, demos/, docs/, tests/, tools/ and the Swift/CMake project files.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset (every file byte-identical to the pin).

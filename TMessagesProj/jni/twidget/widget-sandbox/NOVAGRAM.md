# Vendored: widget-sandbox

Upstream Telegram for Android (DrKLO/Telegram 13.2.2, 21f7d109d) pins this as a git submodule:

- source: https://github.com/TelegramMessenger/widget-sandbox.git
- commit: c498cefddefaba765282c9f3ab0b330f4bc990f6

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. What jni/twidget/twidget.cmake reads: packages/desktop/sandbox (the C++ host, include + src), packages/ios/Sources/CQuickJS/include and shim, packages/core/profile.json (UA stylesheet), plus THIRD_PARTY_NOTICES.md and VERSION.json. QuickJS itself (a submodule of this repository) is vendored at packages/ios/Sources/CQuickJS/quickjs. The JS bundles are in assets/WidgetSandbox, taken from upstream.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset (every file byte-identical to the pin).

# Vendored: wallet-engine (nothing needed)

Upstream Telegram for Android (DrKLO/Telegram 13.0.2, f32ffeec7) pins this as a git submodule:

- source: https://github.com/dkaraush/wallet-engine.git
- commit: 5c65e47cb8d68727c20d89382d0bc51292486b9a

This repository keeps no submodules. Nothing from this Rust workspace is needed to build the app: the Gram Wallet
engine is linked from the prebuilt jni/prebuild/lib/<abi>/libwallet_engine.a, with its generated C++ bindings in
jni/prebuild/include/wallet-engine. The source is only used by jni/prebuild/build_wallet_engine.sh to rebuild
those prebuilts; fetch the commit above if that is ever needed.

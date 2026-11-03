# Vendored: media

Upstream Telegram for Android (DrKLO/Telegram 13.1.1, 3de37c8cf) pins this as a git submodule:

- source: https://github.com/Arseny271/media.git
- commit: 09b6c97c0d691c50cd4541e1573995d4066d6f5c

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. This is the AndroidX Media3 fork that replaced the bundled ExoPlayer2 in 12.10.2, wired in by settings.gradle (core_settings.gradle includes every module under libraries/, so all of their directories must exist). Left out: each module's src/test/ and src/androidTest/ (about 3400 files of test sources and media fixtures, only used to run Media3's own test suites), and the demos/, testapps/ and docsamples/ projects, which core_settings.gradle never includes. The root
build files the modules apply are kept, common_config.gradle among them (new with this pin).

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset.

# Vendored: media

Upstream Telegram for Android (DrKLO/Telegram 12.10.6, f2908b141) pins this as a git submodule:

- source: https://github.com/Arseny271/media.git
- commit: c430d207677071b1873f9f18266d55ec45722180

This repository keeps no submodules, so the needed part is vendored here as plain files and a clone
builds with no extra fetch. This is the AndroidX Media3 fork that replaced the bundled ExoPlayer2 in 12.10.2, wired in by settings.gradle (core_settings.gradle includes every module under libraries/, so all of their directories must exist). Left out: each module's src/test/ and src/androidTest/ (about 3400 files of test sources and media fixtures, only used to run Media3's own test suites), and the demos/ and testapps/ projects, which settings never includes.

To update: check the pinned commit in upstream's tree (`git ls-tree <telegram-rev> <this path>`),
fetch that commit and replace this directory with the same subset.

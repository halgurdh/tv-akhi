# Syncing with upstream TV Bro

TV Akhi is a fork of [TV Bro](https://github.com/truefedex/tv-bro) (`truefedex/tv-bro`, branch `master`).

## Rule

- **Untouched files:** sync freely. Take upstream's version as is.
- **Files changed in this fork:** don't sync them by default. Pull upstream changes into them only for a
  major or breaking fix, such as a new GeckoView/WebView API, a security fix, or a crash fix. Merge
  those by hand and keep the fork's changes.

## Which files the fork has changed

Don't rely on a fixed list. Ask git for it:

```sh
git remote add upstream https://github.com/truefedex/tv-bro.git   # once
git fetch upstream
BASE=$(git merge-base HEAD upstream/master)
git diff --name-only $BASE HEAD          # files changed in this fork
git diff --name-only $BASE upstream/master   # files changed upstream since the fork point
```

A file in both lists needs a manual decision. A file only in the second list is safe to take.

## How to sync

```sh
git fetch upstream
git checkout -b sync-upstream
git merge upstream/master        # conflicts appear only in files both sides changed
```

For each conflicted file, keep the fork's version (`git checkout --ours <file>`) unless the upstream change
is a major or breaking fix. In that case, merge it in by hand.

To pull only the safe part instead of merging everything:

```sh
BASE=$(git merge-base HEAD upstream/master)
comm -13 <(git diff --name-only $BASE HEAD | sort) <(git diff --name-only $BASE upstream/master | sort) \
  | xargs git checkout upstream/master --
```

After syncing, build, run `./gradlew :app:testFossGeckoIncludedDebugUnitTest`, and test on the TV.

## Areas the fork owns (watch these when upstream changes nearby code)

- **Ad blocking:** uBlock Origin extension in GeckoView (`app/gecko/.../UBlockOriginInstaller.kt`) and the
  built-in uBO-compatible engine for WebView (`app/src/main/java/.../adblock/`, `assets/adblock/content.js`).
  Upstream's EasyList blocker (`AdblockModel.kt`, `ad-block` library) was removed. Don't bring it back.
- **Settings and defaults** (`Config.kt`, `MainSettingsView.kt`, `view_settings_main.xml`):
  - GeckoView is the default engine.
  - Yandex is the default search engine.
  - The home page shows most visited sites.
  - The "Open new windows in current tab" setting.
  - The voice search language setting.
- **Branding:** the name "TV Akhi" in all `strings.xml` files, and the credit in `view_settings_version.xml`.
  Upstream's string changes conflict with the rename, so re-apply the rename after taking new upstream strings.
- **Release builds** fall back to debug signing when no keystore is set (`app/build.gradle.kts`).

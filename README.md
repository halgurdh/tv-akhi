# TV Akhi

An ad-free web browser for Android TV, built for the TV remote.

TV Akhi is based on [TV Bro](https://github.com/truefedex/tv-bro) by Fedir Tsapana and adds built-in ad blocking
with uBlock Origin.

## Features

- **Ad blocking with uBlock Origin:**
  - With the GeckoView (Firefox) engine, the default, the real uBlock Origin extension is installed automatically.
  - With the Android WebView engine, a built-in blocker uses uBlock Origin's filter lists (uAssets, EasyList,
    EasyPrivacy, Peter Lowe's, URLhaus), with network filtering, element hiding and scriptlets.
  - You can turn it on or off in Settings or with the toolbar button.
- **Remote-friendly:** works with the TV remote using a virtual cursor, tabs, bookmarks, history, a download manager
  and shortcuts.
- **Home page:** shows your most visited sites. The ⌂ button goes to the home page; pressing it again returns to the TV home screen.
- **Voice search:** pick the language (English, Arabic or the system language) in Settings.
- **Links that open new windows** load in the current tab (can be turned off in Settings).
- **Typing a site name** like `youtube.com` in the address bar opens the site directly.

## Install

Download the APK for your device from [Releases](../../releases):
- `armeabi-v7a`: most Android TVs and TV boxes.
- `arm64-v8a`: newer 64-bit devices.

Then install it, for example with a file manager on the TV or with `adb install`.

## Build

```sh
./gradlew :app:assembleFossGeckoIncludedRelease -PenableAbiSplits
```

Release signing reads `keystore.properties` from the project root:

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

It can also read the `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` environment variables.
Without either, release builds are signed with the local debug key.

Tests: `./gradlew :app:testFossGeckoIncludedDebugUnitTest`

## Credits and license

TV Akhi uses TV Bro's sources: https://github.com/truefedex/tv-bro. See [LICENSE.md](LICENSE.md).
How to take changes from TV Bro: [UPSTREAM_SYNC.md](UPSTREAM_SYNC.md).
Ad blocking uses [uBlock Origin](https://github.com/gorhill/uBlock) and the filter lists from
[uAssets](https://github.com/uBlockOrigin/uAssets).

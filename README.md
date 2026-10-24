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
  - **Scrolling:** a single press of Up or Down scrolls the page (when the cursor is in that half of the screen);
    holding the button moves the cursor and scrolls continuously.
  - **Back:** the first press shows the menu, the second press goes back to the previous page, like a normal browser.
  - **⌂ Home button:** goes to the home page; pressing it again on the home page returns to the TV home screen.
- **Arabic voice search** (البحث الصوتي بالعربية): press the microphone button and speak in Arabic. TV Akhi searches
  for what you said.
  - Choose the language in Settings → **Voice search language**: English (default), العربية (Arabic) or the
    system language.
  - On Android 14 and newer, it can also switch between Arabic and English automatically.
  - Arabic recognition needs a speech service on the TV that supports Arabic, such as Google's.
- **Search:** DuckDuckGo by default. Google, Bing, Yahoo!, Yandex, Startpage or your own search URL can be chosen
  in Settings. Typing a site name like `youtube.com` opens the site directly instead of
  searching.
- **Home page:** shows your most visited sites.
- **Videos:** a video you start goes to fullscreen automatically (Settings → **Play videos in fullscreen**).
- **Links that open new windows** load in the current tab (can be turned off in Settings).

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

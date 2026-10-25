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
- **⏭ Smart skip** (on by default) skips the intro and the end credits of TV show episodes on any site. It leaves a
  2-second safety gap so you don't miss anything:
  - **Known episodes, from the first one:** many streaming sites play TV shows in players that are addressed by the
    show's TMDB number, season and episode (for example `…/tv/1396/1/1`). For those, TV Akhi gets the exact times of
    the **recap**, **intro** and **credits** from [TheIntroDB](https://theintrodb.org), a SponsorBlock-like database
    for TV shows, before the episode plays. This works on sites like himovies.
  - If the site shows its own **Skip intro**, **Skip credits** or **Next episode** button, TV Akhi presses it.
  - **Other sites (intro):** episodes of a show share the same intro. TV Akhi remembers the first minutes of each episode you watch.
    Once two episodes share a sequence, every later episode jumps to 2 seconds before the intro ends. It learns
    from two episodes, so the skipping starts with the third.
  - **End credits:** a credit roll (many lines of text over a dark or frozen picture) near the end of the video is
    skipped after about 10 seconds of it.
  - **Next episode:** after the credits, or when an episode ends, TV Akhi follows the page's "next episode" link
    (الحلقة التالية, or the link to episode N+1), or presses the next episode's button (like "Eps 2"). This also
    works when the player is embedded from another site.
  - While it's still learning a show, or if it can't see the video, a short message says so.
  - Each part is skipped only once per episode. Seek back to watch it again.
  - Only videos longer than 5 minutes count. It can't see inside DRM-protected videos (Netflix, Disney+ and so on).
    On those sites, only the site's own skip buttons are used.
- **🔇 No music** (off by default) removes the background music from videos and keeps the voices. It's based on
  [NoMusic](https://github.com/mohamedsadiqaguenaou/NoMusic) and runs its FastEnhancer Lite speech-enhancement model,
  then RNNoise, on the TV itself; nothing is uploaded.
  - **Silent between sentences:** a voice gate uses RNNoise's voice detection to mute what's left of the music when
    nobody speaks. It looks 30 ms ahead, so the start of a word isn't cut, and it stays open through the short pauses
    between words. Music that only sounds a bit like a voice opens it just a little.
  - A video is filtered from its first second: it starts silent for a moment, then its sound fades in.
  - It works with most streaming players (YouTube and other sites that stream video in chunks). Videos the page
    isn't allowed to process keep their normal sound.
  - It runs in mono on the browser's audio thread, so a busy page can't make the sound stutter.
  - It's a speech model: singing counts as voice, so a sung intro song is only partly removed. Smart skip takes care
    of intros once it has learned them.
  - **Slow TVs:** if the TV can't keep up, No music gets lighter step by step (RNNoise once instead of twice, then
    FastEnhancer alone). It only switches off for that video, and says so, if even that is too much.
- **Links that open new windows** load in the current tab (can be turned off in Settings).
- **🛡 Family filters** (both on by default, in Settings):
  - **Block porn and nudity:** adult sites are blocked, using
    [StevenBlack's porn list](https://github.com/StevenBlack/hosts) (about 64,000 sites). Pages that label themselves
    adult (the RTA label most adult sites use) are closed too, and Google, Bing, DuckDuckGo and Yahoo searches always
    use SafeSearch. With the WebView engine, pictures and videos from adult sites inside other pages are blocked
    as well.
  - **Haram filter:** alcohol, drugs and gambling sites are blocked, using
    [The Block List Project](https://github.com/blocklistproject/Lists) drugs, gambling and vaping lists (about
    300,000 sites). Site names containing words like vodka, whisky, liquor, brewery, casino or poker are blocked too.
    There's no public list of alcohol sites, so some wine shops and similar sites can still get through.
  - **Search words:** searches on Google, Bing, DuckDuckGo, Yahoo, YouTube and other search engines are checked for
    porn, alcohol, drug and gambling words in English, Arabic, Persian, Kurdish, Urdu, Turkish, French, German,
    Dutch, Spanish and Indonesian/Malay (each word follows its filter's switch). Learning about it is allowed,
    getting it isn't: *why is alcohol haram*, *dangers of gambling* or *porn addiction help* search normally, while
    *buy alcohol*, *beer*, *casino near me* or *nude pics* are blocked. A buying or picture/video word always blocks,
    even with a question word (*why buy alcohol*). Searches made inside a page without loading a new one (like
    YouTube's search box) aren't seen.
  - No filter catches everything: a new site, or one that isn't in the lists, can still open.
- **🔒 Parental PIN** (Settings → **Parental PIN**): once a PIN is set, these all ask for the PIN:
  - turning off the porn block, the haram filter or No music;
  - switching to **incognito mode** (coming back out doesn't ask);
  - **deleting browser history**, all of it or single sites;
  - changing or removing the PIN.

  Turning a filter *on* never asks, and without a PIN nothing is locked. The PIN shows as stars while you type it,
  and only a salted hash of it is stored.
  - It protects the settings inside TV Akhi. Someone with access to the TV's Android settings can still clear the
    app's data.

## Using TV Akhi with the remote

### On a web page

| Remote button | What it does |
|---|---|
| **D-pad** (up/down/left/right) | Moves the on-screen cursor. Hold to move faster. At the screen edge, the page scrolls. |
| **Up / Down, single press** | Scrolls the page by a third of a screen when the cursor is in that half of the screen. |
| **OK** | Clicks whatever is under the cursor, like a mouse click. |
| **OK, hold** | Opens the cursor menu (see below). |
| **Back** | First press opens the menu bar. Second press goes back to the previous page. |
| **Play/Pause, Stop, Rewind, Fast forward** | Control the video or music on the page. |
| **Search / mic button** (if your remote has one) | Starts voice search. |

To type, move the cursor onto a text field and press **OK**. The on-screen keyboard opens; **Back** closes it.

**Remote shortcuts:** you can change them, or add one for "Back" or "Home", in Settings → **Shortcuts**.

### Cursor menu (hold OK)

A small round menu appears around the cursor. Move to an option with the D-pad:

- **Grab:** drag the page with the D-pad, like swiping with a finger. Useful for maps and sliders.
- **Zoom in / Zoom out.**
- **Context menu:** for the link under the cursor. Open it in a new tab, open it in another app, download it, copy
  the address, share it, or refresh the page.
- **D-pad mode:** switch off the cursor and jump between links and buttons instead, like a TV app. Press **Back** to
  get the cursor again.

### Menu bar (press Back)

**Top row**, from left to right:
- **✕** closes TV Akhi.
- **🎤** starts voice search.
- **History** shows the sites you visited, where you can delete some or all of them.
- **☆ Bookmarks** shows your saved sites.
- **Downloads.**
- **Incognito mode:** browse without saving history or cookies.
- **⚙ Settings.**
- **Address bar:** type a site (`youtube.com`) or something to search for.

**Tabs row:** all open tabs. Select one to switch to it, or select **+** to open a new tab.

**Bottom row**, from left to right:
- **✕** closes the current tab.
- **←** goes back a page and **→** goes forward.
- **⟳** reloads the page.
- **ads ON / OFF** turns ad blocking on or off. The number next to it shows how many ads were blocked on this page.
- **Pop-up blocker:** choose how strictly this site may open pop-ups.
- **⏭ Smart skip:** turns skipping intros and end credits on or off. When it's off, the icon is crossed out.
- **♪ No music:** turns music removal on or off. A crossed-out note means it's on and the music is removed. A plain
  note means it's off. The change applies to the playing video within a second or two. With a parental PIN set,
  turning it off asks for the PIN.
- **⌂ Home:** goes to the TV Akhi home page. Press it again on the home page to return to the TV's home screen.
  TV Akhi stays open in the background with your tabs.

The **Home button on the remote** always goes straight to the TV's home screen. Android doesn't let apps change that button.

### Home page

The home page shows your **most visited sites**, plus a search box with a microphone and a choice of search
engines. To bookmark a page instead, use **☆ Bookmarks**. You can switch the home page to bookmarks or recent history in
Settings.

### Settings worth knowing

- **Web engine:**
  - **GeckoView** (Firefox) is the default and runs uBlock Origin.
  - **WebView** is Android's built-in engine and uses TV Akhi's own uBlock-compatible blocker.
- **Ad blocking:** on/off.
- **Smart skip** and **No music:** on/off (the same as the ⏭ and ♪ buttons).
- **Block porn and nudity** and **Haram filter:** on/off.
- **Parental PIN:** set, change or remove the PIN that locks the filters, No music, incognito mode and deleting
  history.
- **Play videos in fullscreen:** on/off.
- **Open new windows in current tab:** on/off.
- **Voice search language:** English, العربية or system.
- **Search engine, home page and theme.**
- **Cursor speed and acceleration.**
- **User agent:** shows sites the desktop or the mobile version.

## Install

Download the APK for your TV from the [latest release](https://github.com/halgurdh/tv-akhi/releases/latest):
- `armeabi-v7a`: most Android TVs and TV boxes. If you're not sure, use this one.
- `arm64-v8a`: newer 64-bit devices.

### Option 1: from a laptop over Wi-Fi (adb)

You don't need to copy anything to the TV. The laptop sends the app over your home Wi-Fi.

1. **Install adb on the laptop.** It's part of Google's
   [Platform Tools](https://developer.android.com/tools/releases/platform-tools).
   - Windows: `winget install Google.PlatformTools`
   - macOS: `brew install android-platform-tools`
   - Linux: `sudo apt install adb`
2. **Turn on developer options on the TV.** Go to **Settings → System → About** (on some TVs **Device Preferences →
   About**) and press **OK** seven times on **Android TV OS build** or **Build**. You'll see "You are now a developer".
3. **Turn on network debugging.** In **Settings → System → Developer options**, turn on **USB debugging** and, if the TV
   has it, **Network debugging** or **ADB over network**.
4. **Find the TV's IP address** in **Settings → Network & Internet →** your Wi-Fi network, for example `192.168.1.50`.
   The TV and the laptop must be on the same Wi-Fi.
5. **Connect and install** from a terminal on the laptop, in the folder where you downloaded the APK:

   ```sh
   adb connect 192.168.1.50:5555
   adb install tv-akhi-armeabi-v7a.apk
   ```

   The first time, the TV asks **"Allow USB debugging?"**. Tick **Always allow from this computer** and press **OK**.
   If `adb install` says "unauthorized", accept the prompt on the TV and run it again.

   **Updating:** from 1.1.0 on, TV Akhi updates itself: it checks this repository's releases once a day and offers
   the new version, and **Settings → Version & updates → Check for updates** checks right away (you may have to allow TV Akhi
   to install apps once). Or run the same `adb install` command with `-r`
   (`adb install -r tv-akhi-armeabi-v7a.apk`). Your tabs, bookmarks and history stay.
6. **Open it** from the TV's apps list (**TV Akhi**). You can then turn developer options off again if you like.

**Android 11 and newer:** some TVs only offer **Wireless debugging**, which uses a pairing code instead of port 5555.
In that case:
1. Choose **Pair device with pairing code** on the TV.
2. Run `adb pair <ip>:<pairing port>` on the laptop and enter the code.
3. Run `adb connect <ip>:<port>` with the port shown on the TV.

### Option 2: on the TV itself

1. Download the APK onto a USB stick, or open the release link in a TV app that can download files.
2. Open the APK with a file manager on the TV and choose **Install**.

Android asks once to allow installing apps from that file manager ("unknown sources").

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
Smart skip uses the recap, intro and credits times of [TheIntroDB](https://theintrodb.org).
The family filters use [StevenBlack/hosts](https://github.com/StevenBlack/hosts) (MIT) and
[The Block List Project](https://github.com/blocklistproject/Lists) (Unlicense); `tools/make_filter_lists.py`
rebuilds them.
No music is based on [NoMusic](https://github.com/mohamedsadiqaguenaou/NoMusic) (ISC license) and uses its WebAssembly
build of the [FastEnhancer](https://github.com/aask1357/fastenhancer) Lite model (MIT license) and of
[RNNoise](https://github.com/xiph/rnnoise) (BSD license).

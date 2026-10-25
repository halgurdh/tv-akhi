/* TV Akhi media tools, shared by both engines: injected at document start in WebView (see MediaTools.kt) and a content
 * script of the GeckoView extension (see content.js). The host gives the settings and the model, and shows the messages:
 *   tvakhiMediaTools({ settings: () => ({smartSkip, noMusic}), loadModel: () => Promise<ArrayBuffer>,
 *                      loadDenoiser: () => Promise<ArrayBuffer>,
 *                      workletUrls: () => Promise<[urls of nomusic/engine.js]>, notify: event => {} })
 * nomusic/engine.js must be loaded before this file.
 *
 * Smart skip: skips the intro and the end credits of TV show episodes, keeping a 2 second safety gap.
 *  - Known episodes: players addressed by the TMDB id (/tv/1396/1/1, ?tmdb=1396&season=1&episode=1) get the recap,
 *    intro and credits times from TheIntroDB (https://theintrodb.org, a SponsorBlock for TV shows) before they play.
 *  - The site's own "Skip intro" / "Skip credits" / "Next episode" buttons are pressed when they show up.
 *  - Intro: the episodes of a show share it. The frames of the first minutes of every episode are fingerprinted
 *    (difference hash, 1 per second) and the longest common run of two episodes is saved as the intro of the show.
 *    When a later episode plays the same frames, it jumps to 2 s before the end of the intro. So it learns from 2 episodes.
 *  - End credits: a credit roll is many lines of text over a dark or still picture. 10 frames in a row like this
 *    near the end of the video jump to its end, then the site plays the next episode.
 *  After the credits (or the end of an episode) the next episode of the page is opened.
 *  Each part is skipped once per video: seeking back shows it again.
 *
 * No music: removes the background music and keeps the voices, like NoMusic (https://github.com/mohamedsadiqaguenaou/NoMusic,
 * ISC). Runs its FastEnhancer Lite speech enhancement model (https://github.com/aask1357/fastenhancer, MIT), then
 * RNNoise (https://github.com/xiph/rnnoise, BSD) with a gate that closes between the words, on the video's audio, on
 * the audio thread when the site allows it. A TV too slow for it gets a lighter version, step by step. Only the videos
 * the page can read the audio of (most streaming players): the others keep their sound. Singing is a voice too: it
 * stays.
 */
var tvakhiMediaTools = (function () {
    'use strict';

    const TICK_MS = 1000;
    const SAFETY_S = 2; //the intro is left 2 s before its end
    const MIN_EPISODE_S = 5 * 60; //shorter videos aren't episodes
    const INTRO_WINDOW_S = 6 * 60; //the intro is within the first minutes
    const MIN_INTRO_S = 15; //not a channel logo
    const MAX_INTRO_S = 180; //longer: the same video again
    const MIN_INTRO_MATCHES = 10;
    const MIN_TEMPLATE_VOTES = 7; //matching seconds in a row to trust the intro
    const MAX_DISTANCE = 10; //of 64 bits
    const MIN_CONTRAST = 12; //flat frames (black, a single color) match everything
    const MAX_GAP = 5; //unknown seconds inside a run (black frames, dropped samples)
    const OUTRO_WINDOW_S = 10 * 60; //the credits are within the last minutes
    const MIN_CREDIT_LINES = 4;
    const MIN_CREDIT_FRAMES = 10; //~10 s of a credit roll
    const MAX_CREDIT_MISSES = 1;
    const MAX_STILL_DISTANCE = 4; //a frozen picture behind the credits
    const MAX_RECORDINGS = 8;
    const MAX_TEMPLATES = 20;
    const RECORDINGS_KEY = '__tvakhi_skip_recordings';
    const TEMPLATES_KEY = '__tvakhi_skip_intros';
    const W = 256, H = 144; //analysed frame size

    const INTRO_BUTTON = /^(skip (the )?(intro|recap|opening|introduction|title sequence)|تخط[يى] (المقدمة|المقدّمة|الملخص|البداية))/i;
    const OUTRO_BUTTON = /^(skip (the )?credits|next episode|play next episode|تخط[يى] (الشارة|التترات|النهاية)|الحلقة التالية)/i;

    //---------- frames ----------

    let canvas = null, canvasCtx = null;
    const gray = new Uint8Array(W * H);

    /** Grabs the current frame into gray. False: the page can't read it (a cross-origin or a protected video). */
    function grab(video) {
        if (!canvas) {
            canvas = document.createElement('canvas');
            canvas.width = W;
            canvas.height = H;
            canvasCtx = canvas.getContext('2d', { willReadFrequently: true });
        }
        canvasCtx.drawImage(video, 0, 0, W, H);
        const data = canvasCtx.getImageData(0, 0, W, H).data; //throws on a tainted canvas
        for (let i = 0, p = 0; i < gray.length; i++, p += 4) {
            gray[i] = (data[p] * 299 + data[p + 1] * 587 + data[p + 2] * 114) / 1000;
        }
        return true;
    }

    /** The frame scaled to 9x8 (area average) */
    function small() {
        const out = new Float32Array(72);
        for (let y = 0; y < 8; y++) {
            const y0 = Math.floor(y * H / 8), y1 = Math.floor((y + 1) * H / 8);
            for (let x = 0; x < 9; x++) {
                const x0 = Math.floor(x * W / 9), x1 = Math.floor((x + 1) * W / 9);
                let sum = 0;
                for (let yy = y0; yy < y1; yy++) {
                    for (let xx = x0; xx < x1; xx++) sum += gray[yy * W + xx];
                }
                out[y * 9 + x] = sum / ((y1 - y0) * (x1 - x0));
            }
        }
        return out;
    }

    /** Difference hash as 16 hex chars. Null: a flat frame, unless raw. */
    function hash(px, raw) {
        let mean = 0;
        for (const v of px) mean += v;
        mean /= px.length;
        let variance = 0;
        for (const v of px) variance += (v - mean) * (v - mean);
        if (!raw && Math.sqrt(variance / px.length) < MIN_CONTRAST) return null;

        let hi = 0, lo = 0;
        for (let y = 0; y < 8; y++) {
            for (let x = 0; x < 8; x++) {
                const bit = px[y * 9 + x] > px[y * 9 + x + 1] ? 1 : 0;
                if (y < 4) hi = (hi << 1) | bit; else lo = (lo << 1) | bit;
            }
        }
        return (hi >>> 0).toString(16).padStart(8, '0') + (lo >>> 0).toString(16).padStart(8, '0');
    }

    function bits(n) {
        n = n - ((n >>> 1) & 0x55555555);
        n = (n & 0x33333333) + ((n >>> 2) & 0x33333333);
        return (((n + (n >>> 4)) & 0x0F0F0F0F) * 0x01010101) >>> 24;
    }

    function distance(a, b) {
        return bits((parseInt(a.substring(0, 8), 16) ^ parseInt(b.substring(0, 8), 16)) >>> 0)
            + bits((parseInt(a.substring(8), 16) ^ parseInt(b.substring(8), 16)) >>> 0);
    }

    /** Hashes (hex or null) to numbers, to compare many */
    function parse(list) {
        const n = list.length;
        const parsed = { length: n, hi: new Uint32Array(n), lo: new Uint32Array(n), ok: new Uint8Array(n) };
        for (let i = 0; i < n; i++) {
            const h = list[i];
            if (!h) continue;
            parsed.hi[i] = parseInt(h.substring(0, 8), 16);
            parsed.lo[i] = parseInt(h.substring(8), 16);
            parsed.ok[i] = 1;
        }
        return parsed;
    }

    function near(a, i, b, j) {
        return j >= 0 && j < b.length && a.ok[i] === 1 && b.ok[j] === 1
            && bits(a.hi[i] ^ b.hi[j]) + bits(a.lo[i] ^ b.lo[j]) <= MAX_DISTANCE;
    }

    /** Lines of bright text (a credit roll has many) in the left, the middle or the right part, without the bottom (subtitles) */
    function textLines() {
        return Math.max(textLinesIn(0.05, 0.45), textLinesIn(0.3, 0.7), textLinesIn(0.55, 0.95));
    }

    function textLinesIn(leftPart, rightPart) {
        const left = Math.floor(W * leftPart), right = Math.floor(W * rightPart), bottom = Math.floor(H * 0.8);
        let lines = 0, textRows = 0, isPrevText = false;
        for (let y = 0; y < bottom; y++) {
            let strokes = 0;
            for (let x = left; x < right - 1; x++) {
                const a = gray[y * W + x], b = gray[y * W + x + 1];
                if (Math.abs(a - b) > 30 && Math.max(a, b) > 160) strokes++;
            }
            const isText = strokes >= 3;
            if (isText) textRows++;
            if (isText && !isPrevText) lines++;
            isPrevText = isText;
        }
        //a busy picture (leaves, a crowd) is "text" almost everywhere, the lines of the credits have gaps between them
        return textRows > bottom * 0.6 ? 0 : lines;
    }

    //---------- storage (the site's own storage: the shows of a site) ----------

    function load(key) {
        try { return JSON.parse(localStorage.getItem(key)) || []; } catch (e) { return []; }
    }

    function save(key, value) {
        try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { }
    }

    //---------- intro ----------

    /**
     * The longest run of matching seconds of two episodes (a sample may come a second early or late).
     * {matches, aStart, aEnd} - the first and the last matching second in a.
     */
    function longestRun(listA, listB) {
        const a = parse(listA), b = parse(listB);
        const m = b.length;
        let prevLen = new Int32Array(m + 1), prevMatches = new Int32Array(m + 1), prevGap = new Int32Array(m + 1);
        let len = new Int32Array(m + 1), matches = new Int32Array(m + 1), gap = new Int32Array(m + 1);
        const best = { matches: 0, aStart: -1, aEnd: -1 };

        for (let i = 0; i < a.length; i++) {
            for (let j = 0; j < m; j++) {
                if (!a.ok[i] || !b.ok[j]) {
                    //unknown: the run goes on
                    const isRun = prevLen[j] > 0 && prevGap[j] < MAX_GAP;
                    len[j + 1] = isRun ? prevLen[j] + 1 : 0;
                    matches[j + 1] = isRun ? prevMatches[j] : 0;
                    gap[j + 1] = isRun ? prevGap[j] + 1 : 0;
                } else if (near(a, i, b, j) || near(a, i, b, j - 1) || near(a, i, b, j + 1)) {
                    len[j + 1] = prevLen[j] + 1;
                    matches[j + 1] = prevMatches[j] + 1;
                    gap[j + 1] = 0;
                    if (matches[j + 1] > best.matches) {
                        best.matches = matches[j + 1];
                        best.aStart = i - len[j + 1] + 1;
                        best.aEnd = i;
                    }
                } else {
                    len[j + 1] = 0;
                    matches[j + 1] = 0;
                    gap[j + 1] = 0;
                }
            }
            [prevLen, len] = [len, prevLen];
            [prevMatches, matches] = [matches, prevMatches];
            [prevGap, gap] = [gap, prevGap];
        }

        return best;
    }

    /** Compares the seconds seen so far with the other episodes: a common run that has ended is the intro of the show */
    function learnIntro(state, sec) {
        const recordings = load(RECORDINGS_KEY);
        const templates = load(TEMPLATES_KEY);
        const frames = state.frames.slice(0, INTRO_WINDOW_S);

        //one episode per call: a TV CPU needs ~0.1 s for one, the sound and the picture mustn't wait
        const others = recordings.filter(r => r.id !== state.id);
        if (!others.length) return;
        state.learnIndex = (state.learnIndex + 1) % others.length;
        for (const recording of [others[state.learnIndex]]) {
            const run = longestRun(frames, recording.frames);
            const length = run.aEnd - run.aStart + 1;
            if (run.matches < MIN_INTRO_MATCHES || length < MIN_INTRO_S || length > MAX_INTRO_S
                || run.matches < length * 0.5 || run.aEnd + 5 > sec) {
                continue;
            }
            const intro = frames.slice(run.aStart, run.aEnd + 1).map(h => h || null);
            const known = templates.findIndex(t => longestRun(intro, t.frames).matches >= run.matches * 0.5);
            if (known >= 0) templates.splice(known, 1); //the newer one of the same show
            templates.unshift({ frames: intro });
            save(TEMPLATES_KEY, templates.slice(0, MAX_TEMPLATES));
            state.introLearned = true;
            return;
        }
    }

    function saveRecording(state) {
        const frames = state.frames.slice(0, INTRO_WINDOW_S).map(h => h || null);
        if (frames.filter(h => h).length < 60) return;
        const recordings = load(RECORDINGS_KEY).filter(r => r.id !== state.id);
        recordings.unshift({ id: state.id, frames: frames });
        save(RECORDINGS_KEY, recordings.slice(0, MAX_RECORDINGS));
    }

    /** Votes for the alignments of the playing seconds with the saved intros. Returns the end of the intro (s) or -1. */
    function matchIntro(state, sec, h) {
        if (!state.templates) state.templates = load(TEMPLATES_KEY).map(t => parse(t.frames));
        const current = parse([h]);
        for (let t = 0; t < state.templates.length; t++) {
            const frames = state.templates[t];
            for (let k = 0; k < frames.length; k++) {
                if (!near(current, 0, frames, k)) continue;
                const key = t + ':' + (sec - k);
                let vote = state.votes.get(key);
                if (!vote || sec - vote.last > MAX_GAP + 2) vote = { count: 0, last: -1 };
                if (vote.last === sec) continue;
                vote.count++;
                vote.last = sec;
                state.votes.set(key, vote);

                //neighbor offsets: the samples drift by a second
                let count = vote.count;
                for (const d of [-1, 1]) {
                    const other = state.votes.get(t + ':' + (sec - k + d));
                    if (other && sec - other.last <= MAX_GAP + 2) count += other.count;
                }
                if (count >= MIN_TEMPLATE_VOTES) return sec - k + frames.length;
            }
        }
        return -1;
    }

    //---------- site buttons ----------

    const clicked = new WeakSet();

    /** The site's own skip button near the video */
    function siteButton(video, pattern) {
        let root = video;
        for (let i = 0; i < 6 && root.parentElement; i++) root = root.parentElement;
        for (const el of root.querySelectorAll('button, [role="button"], a')) {
            if (clicked.has(el) || !el.offsetWidth || !el.offsetHeight) continue;
            const label = (el.getAttribute('aria-label') || el.textContent || '').trim().replace(/\s+/g, ' ');
            if (label.length <= 40 && pattern.test(label)) return el;
        }
        return null;
    }

    //---------- next episode ----------

    const NEXT_EPISODE_MESSAGE = 'tvakhi:next-episode';
    const NEXT_LINK = /(الحلقة|الحلقه) (التالية|التاليه)|next episode/i;

    /** The episode number in a title: "الحلقة 89", "Episode 89", "EP89". 0: none */
    function episodeNumber(text) {
        const latin = text.replace(/[٠-٩]/g, d => String(d.charCodeAt(0) - 0x0660));
        const m = latin.match(/(?:الحلقة|الحلقه|حلقة|episode|\beps?\.?)\s*(\d+)/i);
        return m ? parseInt(m[1], 10) : 0;
    }

    /** The "next episode" link of the show page, like the next button in the Java original. Null: the last one. */
    function nextEpisodeUrl() {
        const links = Array.from(document.links).filter(a => a.href && !a.href.startsWith('javascript:'));
        const textOf = a => (a.textContent || '').replace(/\s+/g, ' ').trim();
        const byLabel = links.find(a => NEXT_LINK.test(textOf(a)));
        if (byLabel) return byLabel.href;
        const n = episodeNumber(document.title);
        if (!n) return null;
        const next = links.filter(a => episodeNumber(textOf(a)) === n + 1);
        //the same kind of page (the player page, not the info page) if there are both
        const samePage = next.find(a => new URL(a.href).pathname === location.pathname);
        return (samePage || next[0] || {}).href || null;
    }

    /** The button of the next episode (a list of "Eps 1", "Eps 2" without links). Null: none. */
    function nextEpisodeButton() {
        const labelOf = el => (el.getAttribute('title') || el.textContent || '').replace(/\s+/g, ' ').trim();
        const items = Array.from(document.querySelectorAll('a, button, [role="button"], li'))
            .filter(el => el.offsetWidth && el.offsetHeight && labelOf(el).length <= 80 && episodeNumber(labelOf(el)));
        const key = Array.from(document.querySelectorAll('iframe')).map(f => keyFromUrl(f.src)).find(k => k);
        const active = items.find(el => /(^|\s)(active|current|selected)(\s|$)/.test(el.className));
        const n = (key && key.episode) || (active && episodeNumber(labelOf(active))) || episodeNumber(document.title);
        return n ? items.find(el => episodeNumber(labelOf(el)) === n + 1) || null : null;
    }

    let lastNextAt = 0;

    function openNextEpisode(host) {
        if (Date.now() - lastNextAt < 60000) return; //once per episode: the end and the credits both ask
        lastNextAt = Date.now();
        if (window.top !== window) {
            window.top.postMessage(NEXT_EPISODE_MESSAGE, '*'); //the player is in a frame, the links are in the page
            return;
        }
        const url = nextEpisodeUrl();
        if (url && url !== location.href) {
            host.notify('next_episode');
            location.href = url;
            return;
        }
        const button = nextEpisodeButton();
        if (button) {
            host.notify('next_episode');
            button.click();
        }
    }

    //---------- known episodes (TheIntroDB) ----------

    const INTRODB_URL = 'https://api.theintrodb.org/v2/media';
    const KEY_MESSAGE = 'tvakhi:tmdb:';
    let parentKey = null; //the episode of this frame, from the page around it
    const segments = new Map(); //key -> segments, null: unknown, 'loading'

    /** The episode of a player address: /tv/1396/1/1, /tv/1396/1-1, ?tmdb=1396&season=1&episode=1. Null: none. */
    function keyFromUrl(url) {
        let u;
        try { u = new URL(url, location.href); } catch (e) { return null; }
        const m = u.pathname.match(/\/tv\/(\d+)\/(\d+)[\/-](\d+)(?:\/|$)/);
        if (m) return { id: m[1], season: +m[2], episode: +m[3] };
        const q = u.searchParams;
        const id = q.get('tmdb') || q.get('tmdb_id') || q.get('tmdbId');
        const season = q.get('season') || q.get('s');
        const episode = q.get('episode') || q.get('e') || q.get('ep');
        if (id && /^\d+$/.test(id) && /^\d+$/.test(season || '') && /^\d+$/.test(episode || '')) {
            return { id: id, season: +season, episode: +episode };
        }
        return null;
    }

    function frameKey() {
        return keyFromUrl(location.href) || parentKey;
    }

    /** The players are frames, often in frames: each frame tells its frames which episode they play */
    function passKeys() {
        const own = frameKey();
        for (const frame of Array.from(document.querySelectorAll('iframe'))) {
            const key = keyFromUrl(frame.src) || own;
            if (key && frame.contentWindow) {
                frame.contentWindow.postMessage(KEY_MESSAGE + key.id + ':' + key.season + ':' + key.episode, '*');
            }
        }
    }

    function onKeyMessage(e) {
        if (typeof e.data !== 'string' || !e.data.startsWith(KEY_MESSAGE)) return;
        const parts = e.data.substring(KEY_MESSAGE.length).split(':');
        if (parts.length === 3 && parts.every(v => /^\d+$/.test(v))) {
            parentKey = { id: parts[0], season: +parts[1], episode: +parts[2] };
        }
    }

    /** The recap, intro and credits of the episode. Null: none known (yet). */
    function knownSegments(key) {
        const id = key.id + ':' + key.season + ':' + key.episode;
        if (!segments.has(id)) {
            segments.set(id, 'loading');
            fetch(INTRODB_URL + '?tmdb_id=' + key.id + '&season=' + key.season + '&episode=' + key.episode)
                .then(r => r.ok ? r.json() : null)
                .then(json => segments.set(id, json && json.tmdb_id ? json : null))
                .catch(() => segments.set(id, null));
        }
        const known = segments.get(id);
        return known === 'loading' ? null : known;
    }

    /** Skips by the known times. True: done for this tick. */
    function skipKnown(video, state, known, time, duration, host) {
        const ms = time * 1000, durationMs = duration * 1000;
        const parts = [].concat((known.recap || []).map(r => ['recap', r]), (known.intro || []).map(r => ['intro', r]));
        for (const [kind, part] of parts) {
            const start = part.start_ms || 0, end = part.end_ms;
            //a part longer than the video: another cut of the episode, the times don't fit
            if (end == null || end > durationMs * 0.5 || state.doneParts.has(kind + start)) continue;
            if (kind === 'intro') state.introDone = true; //no learning needed
            if (ms >= start - 500 && ms < end - SAFETY_S * 1000 - 1000) {
                state.doneParts.add(kind + start);
                video.currentTime = end / 1000 - SAFETY_S;
                host.notify(kind === 'recap' ? 'recap_skipped' : 'intro_skipped');
                return true;
            }
        }
        const credits = (known.credits || []).find(c => c.start_ms != null && c.start_ms > durationMs * 0.5 && c.start_ms < durationMs);
        if (credits) {
            state.outroKnown = true; //no credit roll detection needed
            if (!state.outroDone && ms >= credits.start_ms + SAFETY_S * 1000 && time < duration - 3) {
                state.outroDone = true;
                video.currentTime = Math.max(time, duration - 0.5);
                host.notify('credits_skipped');
                openNextEpisode(host);
                return true;
            }
        }
        return false;
    }

    //---------- smart skip ----------

    const states = new WeakMap();

    function stateOf(video) {
        const id = location.origin + location.pathname + location.search + '|' + Math.round(video.duration);
        let state = states.get(video);
        if (!state || state.id !== id) {
            if (state) saveRecording(state);
            state = {
                id: id, frames: [], votes: new Map(), templates: null, blind: false,
                introDone: false, introLearned: false, outroDone: false, isStatusShown: false,
                creditStart: -1, creditFrames: 0, creditMisses: 0, prevStill: null, lastLearn: 0, learnIndex: -1, saved: false,
                doneParts: new Set(), outroKnown: false
            };
            states.set(video, state);
        }
        return state;
    }

    function skipTick(video, host) {
        const duration = video.duration;
        if (!isFinite(duration) || duration < MIN_EPISODE_S || video.paused || video.seeking || video.readyState < 2) return;
        if (video.offsetWidth < 200 || video.offsetHeight < 100) return; //previews
        const state = stateOf(video);
        const time = video.currentTime;
        const sec = Math.floor(time);
        const isOutroZone = time > duration * 0.6 && duration - time < OUTRO_WINDOW_S;

        const key = frameKey();
        const known = key ? knownSegments(key) : null;
        if (known && skipKnown(video, state, known, time, duration, host)) return;
        if (key && !known && segments.get(key.id + ':' + key.season + ':' + key.episode) === 'loading') return;

        if (!state.introDone && time < INTRO_WINDOW_S) {
            const button = siteButton(video, INTRO_BUTTON);
            if (button) {
                clicked.add(button);
                button.click();
                state.introDone = true;
                host.notify('intro_skipped');
                return;
            }
        }
        if (!state.outroDone && isOutroZone) {
            const button = siteButton(video, OUTRO_BUTTON);
            if (button) {
                clicked.add(button);
                button.click();
                state.outroDone = true;
                host.notify('credits_skipped');
                return;
            }
        }

        const isOutroLive = !state.outroDone && !state.outroKnown && isOutroZone;
        if (state.blind || ((time >= INTRO_WINDOW_S || state.introDone) && !isOutroLive)) {
            if (!state.saved && time >= INTRO_WINDOW_S) {
                state.saved = true;
                saveRecording(state);
            }
            return;
        }

        try {
            grab(video);
        } catch (e) {
            state.blind = true; //the page can't read this video
            host.notify('skip_blind');
            return;
        }

        if (time < INTRO_WINDOW_S && !state.introDone) {
            if (!state.isStatusShown) {
                state.isStatusShown = true;
                //no intro of this site's shows known yet: say it learns, else silence looks broken
                if (!load(TEMPLATES_KEY).length) host.notify('skip_learning');
            }
            const h = hash(small(), false);
            state.frames[sec] = h;
            if (!state.introDone && h) {
                const introEnd = matchIntro(state, sec, h);
                if (introEnd > 0) {
                    state.introDone = true;
                    const target = introEnd - SAFETY_S;
                    if (target - time >= 3) {
                        video.currentTime = target;
                        host.notify('intro_skipped');
                    }
                }
            }
            if (!state.introDone && !state.introLearned && Date.now() - state.lastLearn > 5000) {
                state.lastLearn = Date.now();
                learnIntro(state, sec);
            }
            if (sec % 30 === 0) saveRecording(state);
        }

        if (isOutroLive) {
            const still = hash(small(), true);
            const isStill = state.prevStill && distance(still, state.prevStill) <= MAX_STILL_DISTANCE;
            state.prevStill = still;
            const lines = textLines();
            if (lines >= MIN_CREDIT_LINES || (isStill && lines >= 2)) {
                if (state.creditFrames === 0) state.creditStart = time;
                state.creditFrames++;
                state.creditMisses = 0;
            } else if (state.creditFrames > 0 && ++state.creditMisses > MAX_CREDIT_MISSES) {
                state.creditFrames = 0;
            }
            //the credits are seen for 10 s: past the 2 s gap. The end of the video: the site goes to the next episode
            if (state.creditFrames >= MIN_CREDIT_FRAMES && time - state.creditStart >= SAFETY_S) {
                state.outroDone = true;
                video.currentTime = Math.max(time, duration - 0.5);
                host.notify('credits_skipped');
                openNextEpisode(host);
            }
        }
    }

    //---------- no music (the engine: nomusic/engine.js) ----------

    /**
     * One AudioContext for the page: every video (or audio) it filters goes to `input` -> the engine -> `output` ->
     * the speakers. A video is taken as it starts playing; until the engine is ready it is silent (no music in the
     * first second), then the sound fades in. Turned off, the videos go straight to the speakers (a video taken by
     * Web Audio can't leave it).
     */
    function NoMusic(host) {
        const engine = tvakhiNoMusicEngine;
        const FALLBACK_BUFFER = 4096; //the page thread is busy: a big buffer
        //The audio thread's time in the models: over LIGHTEN_LOAD (3 s in a row) one step lighter, the sound stutters
        //above (measured on a TCL TV: 0.70 with underruns, the page and the video took most of a core next to it);
        //over MAX_LOAD with nothing lighter: off.
        const LIGHTEN_LOAD = 0.6, MAX_LOAD = 0.85;
        const FADE_S = 0.3;
        const sources = new Map(); //media element -> its MediaElementAudioSourceNode
        const skipped = new WeakSet(); //the ones it can't read the sound of (said once)
        let model = null, denoiser = null;
        let ctx = null, input = null, output = null, processor = null, chain = null;
        let isEnabled = false, isUnavailable = false, isTooSlow = false, isResuming = false;

        function loadModel() {
            if (!model) {
                model = host.loadModel();
                model.catch(() => { });
            }
            return model;
        }

        /** RNNoise's bytes, null without them (FastEnhancer alone) */
        function loadDenoiser() {
            if (!denoiser) {
                denoiser = host.loadDenoiser ? host.loadDenoiser().catch(e => {
                    console.warn('TV Akhi no music denoiser: ' + e);
                    return null;
                }) : Promise.resolve(null);
            }
            return denoiser;
        }

        /** Routing a video the page can't read through Web Audio would silence it */
        function canFilter(video) {
            if (video.srcObject) return false;
            const src = video.currentSrc || video.src;
            if (!src) return false;
            if (src.startsWith('blob:') || src.startsWith('data:')) return true;
            try {
                return new URL(src, location.href).origin === location.origin || video.crossOrigin !== null;
            } catch (e) {
                return false;
            }
        }

        function filtering() {
            return isEnabled && !isUnavailable && !isTooSlow;
        }

        function route(source) {
            source.disconnect();
            source.connect(filtering() ? input : ctx.destination);
        }

        function routeAll() {
            for (const source of sources.values()) route(source);
        }

        function fadeIn() {
            const now = ctx.currentTime;
            output.gain.cancelScheduledValues(now);
            output.gain.setValueAtTime(0, now);
            output.gain.linearRampToValueAtTime(1, now + FADE_S);
        }

        /** The engine connected only while filtering: it doesn't work for nothing (the fallback would, on the page thread) */
        function connectProcessor() {
            if (!processor) return;
            try { input.disconnect(); } catch (e) { }
            try { processor.disconnect(); } catch (e) { }
            if (!filtering()) return;
            processor.reset();
            input.connect(processor);
            processor.connect(output);
            fadeIn();
        }

        function fail() {
            if (!isUnavailable) host.notify('no_music_unavailable');
            isUnavailable = true;
            if (ctx) routeAll();
        }

        let isLightening = false, canLighten = true;

        /** The load it's too slow above: LIGHTEN_LOAD while there's a lighter step, then MAX_LOAD */
        function maxLoad() {
            return canLighten ? LIGHTEN_LOAD : MAX_LOAD;
        }

        /** Too slow: one step lighter (RNNoise once, then FastEnhancer alone), off only when there's nothing lighter */
        function onSlow() {
            if (isTooSlow || isLightening) return;
            if (canLighten) {
                isLightening = true;
                processor.lighten().then(lighter => {
                    isLightening = false;
                    if (lighter) console.info('TV Akhi no music: too slow, now ' + lighter);
                    else canLighten = false; //the lightest: off only over MAX_LOAD
                });
                return;
            }
            isTooSlow = true; //plain sound is better than a broken one
            routeAll();
            connectProcessor();
            host.notify('no_music_slow');
        }

        /** The models on the audio thread. Null: the site doesn't allow worklets. */
        async function workletNode(ctx, onSlow) {
            if (!ctx.audioWorklet) return null;
            let isLoaded = false;
            for (const url of await host.workletUrls()) {
                try {
                    await ctx.audioWorklet.addModule(url);
                    isLoaded = true;
                    break;
                } catch (e) {
                    console.warn('TV Akhi no music worklet: ' + e);
                }
            }
            if (!isLoaded) return null;

            const node = new AudioWorkletNode(ctx, 'tvakhi-no-music',
                { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [2] });
            const bytes = await loadModel(), denoiserBytes = await loadDenoiser();
            await new Promise((resolve, reject) => {
                const timeout = setTimeout(() => reject(new Error('worklet timeout')), 10000);
                node.port.onmessage = (e) => {
                    clearTimeout(timeout);
                    if (e.data && e.data.ready) resolve(); else reject(new Error(e.data && e.data.error));
                };
                node.port.postMessage({ bytes: bytes, denoiser: denoiserBytes });
            });
            let slow = 0, loads = 0, lightened = null, gateFrames = [0, 0, 0];
            node.port.onmessage = (e) => {
                if (!e.data) return;
                if ('lightened' in e.data && lightened) {
                    lightened(e.data.lightened);
                    lightened = null;
                    slow = 0; //the lighter one gets its own seconds
                }
                if (typeof e.data.load !== 'number') return;
                const gate = e.data.gate || [0, 0, 0];
                gateFrames = gateFrames.map((n, i) => n + gate[i]);
                if (++loads % 5 === 0) {
                    const all = gateFrames[0] + gateFrames[1] + gateFrames[2] || 1;
                    console.info('TV Akhi no music: load ' + e.data.load.toFixed(2) + ', gate closed '
                        + Math.round(gateFrames[0] * 100 / all) + '%, a little ' + Math.round(gateFrames[1] * 100 / all)
                        + '%, open ' + Math.round(gateFrames[2] * 100 / all) + '%');
                    gateFrames = [0, 0, 0];
                }
                slow = e.data.load > maxLoad() ? slow + 1 : 0;
                if (slow >= 3) onSlow();
            };
            node.reset = () => node.port.postMessage({ reset: true });
            node.lighten = () => new Promise(resolve => {
                lightened = resolve;
                node.port.postMessage({ lighten: true });
            });
            return node;
        }

        /** The models on the page thread */
        async function scriptNode(ctx, onSlow) {
            const enhancer = await engine.create(await loadModel(), await loadDenoiser(), ctx.sampleRate);
            const node = ctx.createScriptProcessor(FALLBACK_BUFFER, 2, 2);
            const left = new Float32Array(FALLBACK_BUFFER), right = new Float32Array(FALLBACK_BUFFER);
            const budgetMs = FALLBACK_BUFFER / ctx.sampleRate * 1000;
            let slow = 0;
            node.addEventListener('audioprocess', function (e) {
                const started = performance.now();
                e.inputBuffer.copyFromChannel(left, 0);
                e.inputBuffer.copyFromChannel(right, e.inputBuffer.numberOfChannels > 1 ? 1 : 0);
                enhancer.process(left, right, left, null);
                e.outputBuffer.copyToChannel(left, 0);
                e.outputBuffer.copyToChannel(left, 1);
                slow = performance.now() - started > budgetMs * maxLoad() ? slow + 1 : 0;
                if (slow > 5) {
                    slow = 0;
                    onSlow();
                }
            });
            node.reset = () => enhancer.reset();
            node.lighten = () => Promise.resolve(enhancer.lighten());
            return node;
        }

        function ensureContext() {
            if (ctx) return;
            try {
                ctx = new AudioContext({ sampleRate: engine.MODEL_RATE, latencyHint: 'balanced' });
            } catch (e) {
                ctx = new AudioContext({ latencyHint: 'balanced' });
            }
            input = ctx.createGain();
            output = ctx.createGain();
            output.connect(ctx.destination);
            ctx.addEventListener('statechange', function () {
                if (ctx.state === 'suspended') ctx.resume().catch(() => { });
            });
        }

        /** The engine, built once (a suspended context is enough) */
        function ensureChain() {
            if (chain) return chain;
            chain = (async () => {
                processor = await workletNode(ctx, onSlow).catch(e => {
                    console.warn('TV Akhi no music worklet: ' + e);
                    return null;
                }) || await scriptNode(ctx, onSlow);
                console.info('TV Akhi no music: ready (' + (processor instanceof AudioWorkletNode ? 'audio thread' : 'page thread') + ')');
                connectProcessor();
            })();
            chain.catch(e => {
                console.warn('TV Akhi no music: ' + e);
                fail();
            });
            return chain;
        }

        /** Takes a playing video (once): from then on its sound goes through the context */
        function hook(media) {
            if (sources.has(media) || !filtering() || media.paused) return;
            if (!canFilter(media)) {
                if (!skipped.has(media)) {
                    skipped.add(media);
                    console.info('TV Akhi no music: can\'t read the sound of ' + (media.currentSrc || media.src || 'a video without source'));
                }
                return;
            }
            ensureContext();
            ensureChain();
            if (ctx.state !== 'running') {
                //no user gesture yet: a video taken now would be silent, try again when the context runs
                if (isResuming) return;
                isResuming = true;
                console.info('TV Akhi no music: waiting for the browser to allow sound (' + ctx.state + ')');
                ctx.resume().catch(() => { }).then(() => {
                    isResuming = false;
                    if (ctx.state === 'running') hook(media);
                });
                return;
            }
            let source;
            try {
                source = ctx.createMediaElementSource(media);
            } catch (e) {
                //already taken by the page's own AudioContext: it keeps its sound
                console.warn('TV Akhi no music: ' + e);
                return;
            }
            sources.set(media, source);
            route(source);
            console.info('TV Akhi no music: filtering a ' + media.tagName.toLowerCase() + ' on ' + location.host);
            if (processor) fadeIn(); //else: when the engine is ready
        }

        this.setEnabled = function (enabled) {
            if (enabled === isEnabled) return;
            isEnabled = enabled;
            if (!ctx) return;
            routeAll();
            connectProcessor();
        };

        this.update = function (media, enabled) {
            this.setEnabled(enabled);
            hook(media);
        };
    }

    //---------- adult pages ----------

    //the RTA label ("Restricted To Adults") that adult sites put in their pages, and other adult ratings
    const ADULT_RATING = /RTA-5042-1996-1400-1577-RTA|^\s*(adult|mature|porn|pornographic|xxx)\s*$/i;
    let isAdultChecked = false;

    /** A page that calls itself adult is closed (its site isn't in the list of the app) */
    function checkAdultPage(host) {
        if (isAdultChecked || document.readyState === 'loading') return;
        isAdultChecked = true;
        const metas = document.querySelectorAll('meta[name="rating" i], meta[name="rating:adult" i], meta[http-equiv="pics-label" i]');
        const isAdult = Array.from(metas).some(m => ADULT_RATING.test(m.getAttribute('content') || '')
            || /RTA-5042/i.test(m.getAttribute('content') || ''));
        if (!isAdult) return;
        for (const media of Array.from(document.querySelectorAll('video, audio'))) {
            try { media.pause(); } catch (e) { }
        }
        try { window.stop(); } catch (e) { }
        document.documentElement.innerHTML = '<body style="background:#111"></body>';
        if (window.top === window) host.notify('adult_page'); //the app goes back
    }

    //---------- start ----------

    return function (host) {
        if (window.__tvakhiMediaTools) return;
        window.__tvakhiMediaTools = true;
        const noMusic = new NoMusic(host);
        const videos = document.getElementsByTagName('video');

        window.addEventListener('message', onKeyMessage);

        setInterval(function () {
            const settings = host.settings() || {};
            if (settings.adultBlock) checkAdultPage(host);
            if (settings.smartSkip) passKeys();
            noMusic.setEnabled(!!settings.noMusic);
            if (!videos.length) return;
            for (const video of Array.from(videos)) {
                try {
                    if (settings.smartSkip) skipTick(video, host);
                    noMusic.update(video, !!settings.noMusic);
                } catch (e) {
                    console.warn('TV Akhi media tools: ' + e);
                }
            }
        }, TICK_MS);

        //No music takes a video (or audio) as it starts, not up to a tick later with its first second unfiltered.
        //`play` doesn't bubble: caught on the way down, for every element of the page.
        document.addEventListener('play', function (e) {
            const media = e.target;
            if (!(media instanceof HTMLMediaElement)) return;
            try {
                noMusic.update(media, !!(host.settings() || {}).noMusic);
            } catch (err) {
                console.warn('TV Akhi media tools: ' + err);
            }
        }, true);

        //the end of an episode: the next one, like after the credits
        let isNextOpened = false;
        document.addEventListener('ended', function (e) {
            const video = e.target;
            if (isNextOpened || !(video instanceof HTMLVideoElement) || !(host.settings() || {}).smartSkip) return;
            if (!isFinite(video.duration) || video.duration < MIN_EPISODE_S) return;
            isNextOpened = true;
            openNextEpisode(host);
        }, true);
        if (window.top === window) {
            window.addEventListener('message', function (e) {
                if (e.data === NEXT_EPISODE_MESSAGE && !isNextOpened && (host.settings() || {}).smartSkip) {
                    isNextOpened = true;
                    openNextEpisode(host);
                }
            });
        }

        window.addEventListener('pagehide', function () {
            for (const video of Array.from(videos)) {
                const state = states.get(video);
                if (state) saveRecording(state);
            }
        });
    };
})();

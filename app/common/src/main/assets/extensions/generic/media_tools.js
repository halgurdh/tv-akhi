/* TV Akhi media tools, shared by both engines: injected at document start in WebView (see MediaTools.kt) and a content
 * script of the GeckoView extension (see content.js). The host gives the settings and the model, and shows the messages:
 *   tvakhiMediaTools({ settings: () => ({smartSkip, noMusic}), loadModel: () => Promise<ArrayBuffer>,
 *                      workletUrls: () => Promise<[urls of nomusic/engine.js]>, notify: event => {} })
 * nomusic/engine.js must be loaded before this file.
 *
 * Smart skip: skips the intro and the end credits of TV show episodes, keeping a 2 second safety gap.
 *  - The site's own "Skip intro" / "Skip credits" / "Next episode" buttons are pressed when they show up.
 *  - Intro: the episodes of a show share it. The frames of the first minutes of every episode are fingerprinted
 *    (difference hash, 1 per second) and the longest common run of two episodes is saved as the intro of the show.
 *    When a later episode plays the same frames, it jumps to 2 s before the end of the intro. So it learns from 2 episodes.
 *  - End credits: a credit roll is many lines of text over a dark or still picture. 10 frames in a row like this
 *    near the end of the video jump to its end, then the site plays the next episode.
 *  Each part is skipped once per video: seeking back shows it again.
 *
 * No music: removes the background music and keeps the voices, like NoMusic (https://github.com/mohamedsadiqaguenaou/NoMusic,
 * ISC). Runs its FastEnhancer Lite speech enhancement model (https://github.com/aask1357/fastenhancer, MIT) on the
 * video's audio, on the audio thread when the site allows it. Only the videos the page can read the audio of (most
 * streaming players): the others keep their sound. Singing is a voice too: it stays.
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
        const m = latin.match(/(?:الحلقة|الحلقه|حلقة|episode|\bep\.?)\s*(\d+)/i);
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

    function openNextEpisode(host) {
        if (window.top !== window) {
            window.top.postMessage(NEXT_EPISODE_MESSAGE, '*'); //the player is in a frame, the links are in the page
            return;
        }
        const url = nextEpisodeUrl();
        if (url && url !== location.href) {
            host.notify('next_episode');
            location.href = url;
        }
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
                creditStart: -1, creditFrames: 0, creditMisses: 0, prevStill: null, lastLearn: 0, learnIndex: -1, saved: false
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

        if (state.blind || (time >= INTRO_WINDOW_S && (state.outroDone || !isOutroZone))) {
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

        if (time < INTRO_WINDOW_S) {
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

        if (!state.outroDone && isOutroZone) {
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

    function NoMusic(host) {
        const engine = tvakhiNoMusicEngine;
        const FALLBACK_BUFFER = 4096; //the page thread is busy: a big buffer
        const MAX_LOAD = 0.85;
        const graphs = new WeakMap();
        let model = null;
        let isUnavailable = false;

        function loadModel() {
            if (!model) {
                model = host.loadModel();
                model.catch(() => { });
            }
            return model;
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

        function fail() {
            if (!isUnavailable) host.notify('no_music_unavailable');
            isUnavailable = true;
        }

        /** The model on the audio thread. Null: the site doesn't allow worklets. */
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
            const bytes = await loadModel();
            await new Promise((resolve, reject) => {
                const timeout = setTimeout(() => reject(new Error('worklet timeout')), 10000);
                node.port.onmessage = (e) => {
                    clearTimeout(timeout);
                    if (e.data && e.data.ready) resolve(); else reject(new Error(e.data && e.data.error));
                };
                node.port.postMessage({ bytes: bytes });
            });
            let slow = 0;
            node.port.onmessage = (e) => {
                if (!e.data || typeof e.data.load !== 'number') return;
                slow = e.data.load > MAX_LOAD ? slow + 1 : 0;
                if (slow >= 3) onSlow();
            };
            node.reset = () => node.port.postMessage({ reset: true });
            return node;
        }

        /** The model on the page thread */
        async function scriptNode(ctx, onSlow) {
            const instance = await WebAssembly.instantiate(await WebAssembly.compile(await loadModel()), {});
            const enhancer = new engine.Enhancer(instance, ctx.sampleRate);
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
                slow = performance.now() - started > budgetMs * MAX_LOAD ? slow + 1 : 0;
                if (slow > 5) onSlow();
            });
            node.reset = () => enhancer.reset();
            return node;
        }

        async function build(video) {
            let ctx;
            try {
                ctx = new AudioContext({ sampleRate: engine.MODEL_RATE, latencyHint: 'playback' });
            } catch (e) {
                ctx = new AudioContext({ latencyHint: 'playback' });
            }
            if (ctx.state !== 'running') {
                try { await ctx.resume(); } catch (e) { }
            }
            if (ctx.state !== 'running') { //no user gesture yet: try again later
                ctx.close();
                return null;
            }

            const graph = { enabled: false, isTooSlow: false };
            const onSlow = () => {
                if (graph.isTooSlow) return;
                graph.set(false);
                graph.isTooSlow = true; //plain sound is better than a broken one
                host.notify('no_music_slow');
            };
            let processor;
            try {
                processor = await workletNode(ctx, onSlow).catch(e => {
                    console.warn('TV Akhi no music worklet: ' + e);
                    return null;
                }) || await scriptNode(ctx, onSlow);
            } catch (e) {
                ctx.close();
                throw e;
            }
            //only now: from here the sound of the video goes through the context
            const source = ctx.createMediaElementSource(video);

            graph.set = function (enabled) {
                if (enabled === graph.enabled || (enabled && graph.isTooSlow)) return;
                graph.enabled = enabled;
                source.disconnect();
                processor.disconnect();
                if (enabled) {
                    processor.reset();
                    source.connect(processor);
                    processor.connect(ctx.destination);
                } else {
                    source.connect(ctx.destination);
                }
            };
            ctx.addEventListener('statechange', function () {
                if (ctx.state === 'suspended') ctx.resume().catch(() => { });
            });
            return graph;
        }

        this.update = function (video, enabled) {
            const graph = graphs.get(video);
            if (graph === 'building') return;
            if (graph) {
                graph.set(enabled);
                return;
            }
            if (!enabled || isUnavailable || video.paused || !canFilter(video)) return;

            graphs.set(video, 'building');
            build(video).then(built => {
                if (built) {
                    graphs.set(video, built);
                    built.set(true);
                } else {
                    graphs.delete(video);
                }
            }).catch(e => {
                console.warn('TV Akhi no music: ' + e);
                graphs.delete(video);
                fail();
            });
        };
    }

    //---------- start ----------

    return function (host) {
        if (window.__tvakhiMediaTools) return;
        window.__tvakhiMediaTools = true;
        const noMusic = new NoMusic(host);
        const videos = document.getElementsByTagName('video');

        setInterval(function () {
            if (!videos.length) return;
            const settings = host.settings() || {};
            for (const video of Array.from(videos)) {
                try {
                    if (settings.smartSkip) skipTick(video, host);
                    noMusic.update(video, !!settings.noMusic);
                } catch (e) {
                    console.warn('TV Akhi media tools: ' + e);
                }
            }
        }, TICK_MS);

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

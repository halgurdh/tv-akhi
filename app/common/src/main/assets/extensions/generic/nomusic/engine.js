/* TV Akhi No music engine: FastEnhancer Lite, then RNNoise (both from NoMusic) on the audio of a video. Loaded twice:
 *  - as the AudioWorklet module: the models run on the audio thread, the page can't make it stutter;
 *  - next to media_tools.js, for the fallback on the page thread when the site doesn't allow worklets.
 * One mono stream (the voices are in the middle): half the work of NoMusic's stereo, a TV CPU keeps up.
 * The models are compiled asynchronously: GeckoView only optimizes WebAssembly compiled that way (NoMusic's Firefox
 * build), compiled synchronously FastEnhancer stays several times slower there.
 */
var tvakhiNoMusicEngine = (function () {
    'use strict';

    const MODEL_RATE = 48000;
    const FRAME = 480; //RNNoise: 10 ms at 48 kHz
    const PASSES = 2;
    //The gate on RNNoise's voice probability. NoMusic's (eased per frame from the probability itself) half-opened on
    //music and swallowed the start of words: this one opens on 3 of 4 frames of clear voice, seen 3 frames ahead (the
    //sound is that much late), so the 1-2 frame blips of music don't open it. Music with a voice-like line still gets
    //there now and then, but rarely with the near-certain probabilities of speech (measured: 2-4% of its frames over
    //0.9, against ~45% for speech): until it's sure, the gate only opens a little. After speech it stays open through
    //the pauses between words, after a blip only briefly. It fades within each frame.
    const GATE_OPEN = 0.6; //voice: the gate opens
    const GATE_KEEP = 0.6; //voice again: it stays open (music often scores 0.3-0.6: lower kept it open through music)
    const GATE_SURE = 0.9, GATE_SURE_FRAMES = 2; //that sure, that many frames (of the 4): speech, wide open
    const GATE_UNSURE_GAIN = 0.25; //open, not sure yet
    const GATE_AHEAD = 3; //frames (30 ms)
    const GATE_VOICED = 3; //frames of voice, of the one heard and the ones ahead, to open
    const GATE_SPEECH = 10; //frames (100 ms) of voice: speech
    const GATE_HOLD = 25, GATE_HOLD_BLIP = 8; //frames (250 / 80 ms) without voice before it closes
    const GATE_ATTACK = 0.7, GATE_RELEASE = 0.15; //of the way to open / shut, per frame
    const GATE_WARM_UP = 20; //frames (200 ms): RNNoise's first voice probabilities are high whatever it hears

    function Fifo() {
        let data = new Float32Array(8192), start = 0, end = 0;
        this.length = 0;
        this.push = function (input) {
            if (end + input.length > data.length) {
                const kept = data.subarray(start, end);
                const bigger = end - start + input.length > data.length ? new Float32Array((end - start + input.length) * 2) : data;
                bigger.set(kept, 0);
                end -= start;
                start = 0;
                data = bigger;
            }
            data.set(input, end);
            end += input.length;
            this.length = end - start;
        };
        /** Pads with silence */
        this.pop = function (output, count) {
            const n = Math.min(count, end - start);
            output.set(data.subarray(start, start + n), 0);
            output.fill(0, n, count);
            start += n;
            if (start === end) start = end = 0;
            this.length = end - start;
        };
    }

    /** Linear resampler, ratio: output rate / input rate */
    function Resampler(ratio) {
        const step = 1 / ratio;
        let pos = 0, last = 0, out = new Float32Array(0);
        this.run = function (input) {
            if (ratio === 1) return input;
            const n = input.length;
            const max = Math.ceil((n + 1) * ratio) + 2;
            if (out.length < max) out = new Float32Array(max);
            let count = 0, p = pos;
            while (p < n - 1) {
                const i = Math.floor(p), frac = p - i;
                const a = i < 0 ? last : input[i];
                out[count++] = a + (input[i + 1] - a) * frac;
                p += step;
            }
            pos = p - n;
            last = input[n - 1];
            return out.subarray(0, count);
        };
    }

    /**
     * RNNoise on FastEnhancer's output, twice, and a gate that closes while nobody speaks: what FastEnhancer leaves of
     * the music between the words. A frame at a time (step), so it can take turns with FastEnhancer: 30 ms of latency
     * (the gate's look ahead).
     */
    function Denoiser(rn, heap) {
        const states = [];
        for (let p = 0; p < PASSES; p++) {
            const state = rn.f(0); //rnnoise_create
            if (!state) throw new Error('rnnoise_create');
            states.push(state);
        }
        const inPtr = rn.g(FRAME * 4), outPtr = rn.g(FRAME * 4); //malloc
        if (!inPtr || !outPtr) throw new Error('rnnoise malloc');
        const input = new Fifo();
        const frame = new Float32Array(FRAME);
        let gain = 0, passes = PASSES;
        //the frames waiting for the gate to see ahead (a ring: `next` is the oldest), and their voice probabilities
        const waiting = [], vads = new Float32Array(GATE_AHEAD), gated = new Float32Array(FRAME);
        for (let i = 0; i < GATE_AHEAD; i++) waiting.push(new Float32Array(FRAME));
        let next = 0, isOpen = false, isSpeech = false, hold = 0, voicedSince = 0, warmUp = GATE_WARM_UP;
        const gateFrames = [0, 0, 0]; //frames closed, open a little, wide open (since stats())

        /** The gate's frames closed, open a little and wide open since the last call */
        this.stats = function () {
            const result = gateFrames.slice();
            gateFrames.fill(0);
            return result;
        };

        /** `frame` just denoised, its voice probability `vad`: returns the frame GATE_AHEAD earlier, gated */
        function gate(vad) {
            if (warmUp > 0) {
                warmUp--;
                vad = 0;
            }
            gated.set(waiting[next]);
            const gatedVad = vads[next];
            waiting[next].set(frame);
            vads[next] = vad;
            next = (next + 1) % waiting.length;

            //what's heard now and the next frames
            let voiced = gatedVad >= GATE_OPEN ? 1 : 0, sure = gatedVad >= GATE_SURE ? 1 : 0, most = gatedVad;
            for (let i = 0; i < vads.length; i++) {
                if (vads[i] >= GATE_OPEN) voiced++;
                if (vads[i] >= GATE_SURE) sure++;
                most = Math.max(most, vads[i]);
            }
            if (!isOpen && voiced >= GATE_VOICED) {
                isOpen = true;
                isSpeech = false;
                voicedSince = 0;
                hold = GATE_HOLD_BLIP;
            } else if (isOpen) {
                if (gatedVad >= GATE_OPEN) voicedSince++;
                if (most >= GATE_KEEP) hold = isSpeech ? GATE_HOLD : GATE_HOLD_BLIP;
                else if (--hold <= 0) isOpen = false;
            }
            if (isOpen && (sure >= GATE_SURE_FRAMES || voicedSince >= GATE_SPEECH)) isSpeech = true;

            const target = !isOpen ? 0 : isSpeech ? 1 : GATE_UNSURE_GAIN;
            gateFrames[!isOpen ? 0 : isSpeech ? 2 : 1]++;
            const to = gain + (target - gain) * (target > gain ? GATE_ATTACK : GATE_RELEASE);
            //from one frame's gain to the next one's across the frame: no step, no click
            for (let i = 0; i < FRAME; i++) gated[i] *= gain + (to - gain) * (i + 1) / FRAME;
            gain = to < 0.001 ? 0 : to;
            return gated;
        }

        this.push = function (samples) {
            input.push(samples);
        };

        /** A whole frame is waiting */
        this.pending = function () {
            return input.length >= FRAME;
        };

        /** One frame through RNNoise and the gate: returns it (the array is reused) */
        this.step = function () {
            input.pop(frame, FRAME);
            const inAt = inPtr >>> 2, outAt = outPtr >>> 2;
            let h = heap();
            for (let i = 0; i < FRAME; i++) h[inAt + i] = frame[i] * 32768;
            let vad = 0;
            for (let p = 0; p < passes; p++) {
                vad = rn.j(states[p], outPtr, inPtr); //rnnoise_process_frame
                h = heap();
                if (p < passes - 1) h.copyWithin(inAt, outAt, outAt + FRAME);
            }
            for (let i = 0; i < FRAME; i++) frame[i] = h[outAt + i] / 32768;
            return gate(vad);
        };

        /** A slow TV: one pass of RNNoise instead of two. False if it was already one. */
        this.onePass = function () {
            if (passes === 1) return false;
            passes = 1;
            return true;
        };

        this.reset = function () {
            gain = 0;
            isOpen = false;
            isSpeech = false;
            hold = 0;
            voicedSince = 0;
        };
    }

    /** RNNoise's Emscripten build: its two imports (growing the memory, memcpy) */
    async function createDenoiser(bytes) {
        let memory, heapU8, heapF32;
        const views = function () {
            heapU8 = new Uint8Array(memory.buffer);
            heapF32 = new Float32Array(memory.buffer);
        };
        const imports = {
            a: {
                a: function (size) {
                    size >>>= 0;
                    const old = heapU8.length, max = 2147483648;
                    if (size > max) return 0;
                    for (let cut = 1; cut <= 4; cut *= 2) {
                        const grown = Math.min(old * (1 + 0.2 / cut), size + 100663296);
                        const next = Math.min(max, (Math.max(size, grown) + 65535) & ~65535);
                        try {
                            memory.grow((next - heapU8.byteLength + 65535) >>> 16);
                            views();
                            return 1;
                        } catch (e) { }
                    }
                    return 0;
                },
                b: function (dest, src, num) {
                    heapU8.copyWithin(dest, src, src + num);
                }
            }
        };
        const rn = (await WebAssembly.instantiate(bytes, imports)).instance.exports;
        memory = rn.c;
        views();
        rn.d(); //the module's constructors
        return new Denoiser(rn, function () {
            if (heapF32.buffer !== memory.buffer) views();
            return heapF32;
        });
    }

    /** FastEnhancer Lite and its stream. The views on its memory are made once: no garbage on the audio thread. */
    function Model(instance) {
        const fe = instance.exports;
        const hop = fe.fe_hop_size(), handle = fe.fe_stream_new();
        let buffer = null, inView = null, outView = null;
        this.hop = hop;

        /** A hop through the model: its output (valid until the next call) */
        this.run = function (samples) {
            if (fe.memory.buffer !== buffer) {
                buffer = fe.memory.buffer;
                inView = new Float32Array(buffer, fe.fe_stream_input(handle), hop);
                outView = new Float32Array(buffer, fe.fe_stream_output(handle), hop);
            }
            inView.set(samples);
            fe.fe_stream_process(handle, 1);
            return outView;
        };

        this.reset = function () {
            fe.fe_stream_reset(handle);
        };
    }

    const QUANTUM = 128; //a worklet's block
    //The output starts this far ahead (samples at the output rate, at least this many): a block runs one model call at
    //most, so a hop's result, then its frames through RNNoise, come a few blocks after its last sample.
    const OUTPUT_AHEAD = 1024;

    /**
     * FastEnhancer on the mix of both channels, then the denoiser (if any), the result goes to both. One model call per
     * block of 128 (a hop of FastEnhancer or a frame of RNNoise): a slow TV never has to fit both in one block, which
     * made the sound stutter. ~50 ms of latency.
     */
    function Enhancer(model, rate, denoiser) {
        const hop = model.hop;
        const down = new Resampler(MODEL_RATE / rate), up = new Resampler(rate / MODEL_RATE);
        const input = new Fifo(), output = new Fifo();
        const hopBuffer = new Float32Array(hop);
        let mix = new Float32Array(0);
        let isDenoiserOff = !denoiser;

        output.push(new Float32Array(Math.ceil(OUTPUT_AHEAD * Math.max(1, rate / MODEL_RATE))));

        /**
         * The TV is too slow: one step lighter (RNNoise once instead of twice, then FastEnhancer alone). Returns what
         * plays now, null when there's nothing lighter.
         */
        this.lighten = function () {
            if (!isDenoiserOff && denoiser.onePass()) return 'one pass of RNNoise';
            if (!isDenoiserOff) {
                isDenoiserOff = true;
                return 'FastEnhancer alone';
            }
            return null;
        };

        /** left/right: input, outLeft/outRight: output (may be the same arrays), outRight may be null */
        this.process = function (left, right, outLeft, outRight) {
            const n = left.length;
            if (mix.length !== n) mix = new Float32Array(n);
            for (let i = 0; i < n; i++) mix[i] = (left[i] + right[i]) * 0.5;
            input.push(down.run(mix));
            //RNNoise first: it works on what FastEnhancer already gave it (a bigger block: as many calls as blocks of 128)
            for (let calls = Math.max(1, Math.round(n / QUANTUM)); calls > 0; calls--) {
                if (!isDenoiserOff && denoiser.pending()) {
                    output.push(up.run(denoiser.step()));
                } else if (input.length >= hop) {
                    input.pop(hopBuffer, hop);
                    const enhanced = model.run(hopBuffer);
                    if (isDenoiserOff) output.push(up.run(enhanced));
                    else denoiser.push(enhanced);
                } else {
                    break;
                }
            }
            output.pop(outLeft, n);
            if (outRight) outRight.set(outLeft);
        };

        this.reset = function () {
            model.reset();
            if (denoiser) denoiser.reset();
        };

        /** The gate's frames closed, open a little and wide open since the last call (null without the gate) */
        this.gateStats = function () {
            return isDenoiserOff ? null : denoiser.stats();
        };
    }

    /** The engine from the models' bytes (no denoiser bytes, or a denoiser that fails: FastEnhancer alone) */
    async function create(modelBytes, denoiserBytes, rate) {
        const model = new Model((await WebAssembly.instantiate(modelBytes, {})).instance);
        let denoiser = null;
        if (denoiserBytes) {
            try {
                denoiser = await createDenoiser(denoiserBytes);
            } catch (e) {
                console.warn('TV Akhi no music denoiser: ' + e);
            }
        }
        return new Enhancer(model, rate, denoiser);
    }

    return { MODEL_RATE: MODEL_RATE, Fifo: Fifo, Resampler: Resampler, Enhancer: Enhancer, create: create };
})();

if (typeof registerProcessor === 'function') {
    /**
     * Messages in: {bytes, denoiser} the models (FastEnhancer Lite, RNNoise), {reset}, {lighten} one step lighter.
     * Out: {ready}, {error}, {lightened} what plays after {lighten} (null: nothing lighter), every second {load} the
     * part of the audio time spent in the models (near 1: the TV is too slow) and {gate} its frames closed, open a
     * little and wide open.
     */
    class TVAkhiNoMusicProcessor extends AudioWorkletProcessor {
        constructor() {
            super();
            this.enhancer = null;
            this.busyMs = 0;
            this.since = currentTime;
            this.port.onmessage = (e) => {
                const data = e.data || {};
                if (data.bytes) {
                    tvakhiNoMusicEngine.create(data.bytes, data.denoiser, sampleRate).then(enhancer => {
                        this.enhancer = enhancer;
                        this.port.postMessage({ ready: true });
                    }).catch(err => this.port.postMessage({ error: String(err) }));
                }
                if (data.reset && this.enhancer) this.enhancer.reset();
                if (data.lighten && this.enhancer) this.port.postMessage({ lightened: this.enhancer.lighten() });
            };
        }

        process(inputs, outputs) {
            const input = inputs[0], output = outputs[0];
            if (!input || !input.length || !this.enhancer) {
                for (let c = 0; c < output.length; c++) {
                    if (input && input[c]) output[c].set(input[c]); else output[c].fill(0);
                }
                return true;
            }
            const started = Date.now();
            this.enhancer.process(input[0], input[1] || input[0], output[0], output[1] || null);
            this.busyMs += Date.now() - started;
            if (currentTime - this.since >= 1) {
                this.port.postMessage({
                    load: this.busyMs / ((currentTime - this.since) * 1000),
                    gate: this.enhancer.gateStats()
                });
                this.busyMs = 0;
                this.since = currentTime;
            }
            return true;
        }
    }

    registerProcessor('tvakhi-no-music', TVAkhiNoMusicProcessor);
}

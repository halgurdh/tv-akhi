/* TV Akhi No music engine: FastEnhancer (from NoMusic) on the audio of a video. Loaded twice:
 *  - as the AudioWorklet module: the model runs on the audio thread, the page can't make it stutter;
 *  - next to media_tools.js, for the fallback on the page thread when the site doesn't allow worklets.
 * One mono stream (the voices are in the middle): half the work of NoMusic's stereo, a TV CPU keeps up.
 */
var tvakhiNoMusicEngine = (function () {
    'use strict';

    const MODEL_RATE = 48000;

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

    /** FastEnhancer on the mix of both channels, the result goes to both. A hop (~11 ms) of latency. */
    function Enhancer(instance, rate) {
        const fe = instance.exports;
        const hop = fe.fe_hop_size();
        const handle = fe.fe_stream_new();
        const down = new Resampler(MODEL_RATE / rate), up = new Resampler(rate / MODEL_RATE);
        const input = new Fifo(), output = new Fifo();
        const hopBuffer = new Float32Array(hop);
        let mix = new Float32Array(0);

        //the input comes in blocks smaller than a hop (128 samples in a worklet)
        output.push(new Float32Array(Math.ceil(hop * rate / MODEL_RATE)));

        /** left/right: input, outLeft/outRight: output (may be the same arrays), outRight may be null */
        this.process = function (left, right, outLeft, outRight) {
            const n = left.length;
            if (mix.length !== n) mix = new Float32Array(n);
            for (let i = 0; i < n; i++) mix[i] = (left[i] + right[i]) * 0.5;
            input.push(down.run(mix));
            while (input.length >= hop) {
                input.pop(hopBuffer, hop);
                new Float32Array(fe.memory.buffer, fe.fe_stream_input(handle), hop).set(hopBuffer);
                fe.fe_stream_process(handle, 1);
                output.push(up.run(new Float32Array(fe.memory.buffer, fe.fe_stream_output(handle), hop)));
            }
            output.pop(outLeft, n);
            if (outRight) outRight.set(outLeft);
        };

        this.reset = function () {
            fe.fe_stream_reset(handle);
        };
    }

    return { MODEL_RATE: MODEL_RATE, Fifo: Fifo, Resampler: Resampler, Enhancer: Enhancer };
})();

if (typeof registerProcessor === 'function') {
    /**
     * Messages in: {bytes} the model, {reset}. Out: {ready}, {error}, {load} the part of the audio time spent in the
     * model (every second; near 1: the TV is too slow).
     */
    class TVAkhiNoMusicProcessor extends AudioWorkletProcessor {
        constructor() {
            super();
            this.enhancer = null;
            this.busyMs = 0;
            this.since = currentTime;
            this.port.onmessage = (e) => {
                const data = e.data || {};
                try {
                    if (data.bytes) {
                        const instance = new WebAssembly.Instance(new WebAssembly.Module(data.bytes), {});
                        this.enhancer = new tvakhiNoMusicEngine.Enhancer(instance, sampleRate);
                        this.port.postMessage({ ready: true });
                    }
                    if (data.reset && this.enhancer) this.enhancer.reset();
                } catch (err) {
                    this.port.postMessage({ error: String(err) });
                }
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
                this.port.postMessage({ load: this.busyMs / ((currentTime - this.since) * 1000) });
                this.busyMs = 0;
                this.since = currentTime;
            }
            return true;
        }
    }

    registerProcessor('tvakhi-no-music', TVAkhiNoMusicProcessor);
}

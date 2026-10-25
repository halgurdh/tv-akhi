// This is a leftover from the removal of the abandoned "text selection" feature.
// I'll leave it here for now as a framework for future extension-based features.

console.log("TV Bro generic content extension loaded");

window.addEventListener('load', function () {
    //document.body.style.userSelect = "none";
    console.log("window.load executed");
});

const communicatePort = browser.runtime.connectNative("tvbro_content");

communicatePort.onMessage.addListener(message => {
    console.log("Received message from native app:", message);
});
(function () {
    //auto fullscreen: when a video starts playing after user action, make its player fullscreen
    function setupAutoFullscreen(isEnabled) {
        const handled = new WeakSet();
        //the player container usually has the same size as the video and holds site's own controls
        function fullscreenTarget(video) {
            const r = video.getBoundingClientRect();
            let target = video;
            for (let p = video.parentElement; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
                const pr = p.getBoundingClientRect();
                if (pr.width > r.width * 1.15 + 4 || pr.height > r.height * 1.15 + 4) break;
                target = p;
            }
            return target;
        }
        document.addEventListener('play', function (e) {
            const video = e.target;
            if (!(video instanceof HTMLVideoElement) || handled.has(video)) return;
            if (document.fullscreenElement || !isEnabled()) return;
            const r = video.getBoundingClientRect();
            if (r.width < 200 || r.height < 100) return; //previews and tiny players
            handled.add(video);
            try {
                const p = fullscreenTarget(video).requestFullscreen();
                if (p && p.catch) p.catch(function () { handled.delete(video); });
            } catch (err) {
                handled.delete(video);
            }
        }, true);
    }

    let settings = {};
    function refreshSettings() {
        browser.runtime.sendNativeMessage("tvbro_content", { action: "getSettings" })
            .then(result => { settings = result || {}; })
            .catch(() => { });
    }
    refreshSettings();
    //the toolbar toggles change while the video plays
    const videos = document.getElementsByTagName('video');
    setInterval(() => { if (videos.length) refreshSettings(); }, 2000);
    setupAutoFullscreen(() => !!settings.autoFullscreenVideo);

    //media_tools.js
    tvakhiMediaTools({
        settings: () => settings,
        loadModel: () => fetch(browser.runtime.getURL("nomusic/fastenhancer_t.wasm")).then(r => r.arrayBuffer()),
        loadDenoiser: () => fetch(browser.runtime.getURL("nomusic/rnnoise.wasm")).then(r => r.arrayBuffer()),
        //the extension file, or a copy in a blob if the page refuses it
        workletUrls: () => fetch(browser.runtime.getURL("nomusic/engine.js")).then(r => r.text())
            .then(source => [browser.runtime.getURL("nomusic/engine.js"),
                URL.createObjectURL(new Blob([source], { type: "application/javascript" }))])
            .catch(() => [browser.runtime.getURL("nomusic/engine.js")]),
        notify: event => {
            browser.runtime.sendNativeMessage("tvbro_content", { action: "mediaToolsEvent", event: event }).catch(() => { });
        }
    });
})();

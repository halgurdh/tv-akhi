/* TV Akhi: injected at document start in WebView. Same logic as in the GeckoView extension content.js */
(function () {
    'use strict';
    if (!window.TVBro || window.__tvakhiAutoFullscreen) return;
    window.__tvakhiAutoFullscreen = true;
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

    setupAutoFullscreen(function () {
        try { return window.TVBro.isAutoFullscreenVideoEnabled(); } catch (e) { return false; }
    });
})();

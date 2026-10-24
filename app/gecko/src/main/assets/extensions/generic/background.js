console.log("TV Bro background extension loaded");

//ad blocking is done by uBlock Origin extension, this port is kept for app <-> extension messaging
let tvBroPort = browser.runtime.connectNative("tvbro_bg");

//serve home page from files bundled with the extension (TV Akhi branding) instead of the upstream web version;
//the url stays the same so home.js content script keeps working
const HOME_PAGE_PREFIX = "https://tvbro.phlox.dev/appcontent/home/";

browser.webRequest.onBeforeRequest.addListener(
    function (details) {
        let path = details.url.substring(HOME_PAGE_PREFIX.length).split(/[?#]/)[0];
        if (path === "" || path === "/") path = "index.html";
        if (path.includes("..")) return {};
        let filter = browser.webRequest.filterResponseData(details.requestId);
        //filter can be written only after it started
        let started = new Promise(resolve => { filter.onstart = resolve; });
        let bundled = fetch(browser.runtime.getURL("home/" + path))
            .then(r => r.ok ? r.arrayBuffer() : null)
            .catch(() => null);
        filter.ondata = () => { }; //drop server data
        Promise.all([started, bundled]).then(([, buffer]) => {
            try {
                if (buffer) {
                    filter.write(buffer);
                    filter.close();
                } else {
                    //not bundled - pass the server response through
                    filter.disconnect();
                }
            } catch (e) {
                console.error("home page filter error: " + e);
            }
        });
        return {};
    },
    { urls: [HOME_PAGE_PREFIX + "*"] },
    ["blocking"]
);

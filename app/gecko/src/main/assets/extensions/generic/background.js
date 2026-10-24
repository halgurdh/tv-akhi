console.log("TV Bro background extension loaded");

//ad blocking is done by uBlock Origin extension, this port is kept for app <-> extension messaging
let tvBroPort = browser.runtime.connectNative("tvbro_bg");

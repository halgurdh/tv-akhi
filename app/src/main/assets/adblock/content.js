/*
 * TV Bro ad blocker content script for WebView (uBlock Origin compatible subset).
 * Injected at document start into every frame. Gets cosmetic filters and scriptlets for the
 * current frame from the app through the TVBroAdblock JS interface.
 */
(function () {
    'use strict';
    const bridge = window.TVBroAdblock;
    if (!bridge || window.__tvbroAdblockInjected) return;
    try {
        Object.defineProperty(window, '__tvbroAdblockInjected', { value: true });
    } catch (e) {
        return;
    }
    let data;
    try {
        data = JSON.parse(bridge.getPageData(location.href));
    } catch (e) {
        return;
    }
    if (!data) return;

    //natives captured before page scripts can change them
    const nativeSetTimeout = window.setTimeout.bind(window);
    const nativeClearTimeout = window.clearTimeout.bind(window);
    const nativeJSONParse = JSON.parse;
    const nativeJSONStringify = JSON.stringify;
    const NativeMutationObserver = window.MutationObserver;
    const nativeQSA = Document.prototype.querySelectorAll;
    const nativeElQSA = Element.prototype.querySelectorAll;
    const nativeMatches = Element.prototype.matches;
    const nativeClosest = Element.prototype.closest;
    const nativeFetch = window.fetch;

    // ---------------------------------------------------------------- helpers

    function patternToRegex(pattern, flags, verbatim) {
        if (pattern === undefined || pattern === '') return /^/;
        const m = /^\/(.+)\/([gimsu]*)$/.exec(pattern);
        if (m) {
            try {
                return new RegExp(m[1], flags !== undefined && m[2] === '' ? flags : m[2]);
            } catch (e) {
                return /\b\B/; //never matches
            }
        }
        const escaped = pattern.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        return new RegExp(verbatim ? '^' + escaped + '$' : escaped, flags || '');
    }

    /** needle with optional leading `!` (negation) */
    function makeMatcher(needle) {
        let negate = false;
        if (needle && needle.startsWith('!')) { negate = true; needle = needle.slice(1); }
        const re = patternToRegex(needle);
        return s => re.test(s) !== negate;
    }

    function onDomReady(fn) {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', () => fn(), { once: true });
        } else {
            fn();
        }
    }

    /** runs fn now, at DOM ready and on DOM mutations (throttled) */
    function runAndObserve(fn) {
        let timer;
        const run = () => { timer = undefined; try { fn(); } catch (e) { } };
        run();
        onDomReady(run);
        const observer = new NativeMutationObserver(() => {
            if (timer === undefined) timer = nativeSetTimeout(run, 50);
        });
        const start = () => observer.observe(document, { childList: true, subtree: true, attributes: false });
        if (document.documentElement) start(); else onDomReady(start);
    }

    function addStyle(css) {
        if (!css) return;
        const style = document.createElement('style');
        style.textContent = css;
        const append = () => {
            const parent = document.head || document.documentElement;
            if (!parent) return false;
            parent.appendChild(style);
            return true;
        };
        if (!append()) onDomReady(append);
        //keep our style in place if page rewrites head
        onDomReady(() => { if (!style.isConnected) append(); });
    }

    /** resolves `a.b.c` chain owner, returns [owner, prop] or null */
    function resolveChain(chain) {
        let owner = window;
        if (chain.startsWith('window.')) chain = chain.slice(7);
        const parts = chain.split('.');
        for (let i = 0; i < parts.length - 1; i++) {
            const v = owner[parts[i]];
            if (v === null || (typeof v !== 'object' && typeof v !== 'function')) return null;
            owner = v;
        }
        return [owner, parts[parts.length - 1]];
    }

    /**
     * Traps property chain on window: when all objects of the chain exist (now or later),
     * calls onOwner(owner, prop) for the final property.
     */
    function trapChain(chain, onOwner) {
        if (chain.startsWith('window.')) chain = chain.slice(7);
        const walk = (owner, chain) => {
            const pos = chain.indexOf('.');
            if (pos === -1) { onOwner(owner, chain); return; }
            const prop = chain.slice(0, pos);
            const rest = chain.slice(pos + 1);
            const current = owner[prop];
            if (current instanceof Object || (typeof current === 'object' && current !== null)) {
                walk(current, rest);
                return;
            }
            const desc = Object.getOwnPropertyDescriptor(owner, prop);
            if (desc && !desc.configurable) return;
            let value = current;
            try {
                Object.defineProperty(owner, prop, {
                    configurable: true,
                    enumerable: true,
                    get() { return value; },
                    set(v) {
                        value = v;
                        if (v instanceof Object) walk(v, rest);
                    }
                });
            } catch (e) { }
        };
        walk(window, chain);
    }

    const noopFunc = function () { };
    const trueFunc = function () { return true; };
    const falseFunc = function () { return false; };

    function resolveValue(raw, trusted) {
        switch (raw) {
            case 'undefined': return { v: undefined };
            case 'false': return { v: false };
            case 'true': return { v: true };
            case 'null': return { v: null };
            case "''": case '': case 'emptyStr': return { v: '' };
            case 'noopFunc': return { v: noopFunc };
            case 'trueFunc': return { v: trueFunc };
            case 'falseFunc': return { v: falseFunc };
            case 'throwFunc': return { v: function () { throw new Error(); } };
            case 'emptyObj': case '{}': return { v: {} };
            case 'emptyArr': case '[]': return { v: [] };
            case 'noopCallbackFunc': return { v: function () { return noopFunc; } };
            case 'noopPromiseResolve': return { v: function () { return Promise.resolve(); } };
            case 'noopPromiseReject': return { v: function () { return Promise.reject(); } };
            case 'yes': case 'no': case 'on': case 'off': case 'accept': case 'reject': return { v: raw };
            case '-1': return { v: -1 };
            case '-0': return { v: -0 };
        }
        if (/^\d+$/.test(raw)) {
            const n = parseInt(raw, 10);
            if (trusted || n <= 32767) return { v: n };
            return null;
        }
        if (trusted) {
            try {
                const parsed = nativeJSONParse(raw);
                if (parsed && typeof parsed === 'object' && 'value' in parsed) return { v: parsed.value };
                return { v: parsed };
            } catch (e) {
                return { v: raw };
            }
        }
        return null;
    }

    /** `key:value key2:/regex/` or bare value (applies to url) */
    function parsePropsToMatch(spec, defaultProp) {
        const result = [];
        if (!spec) return result;
        for (const part of spec.split(/\s+/)) {
            if (!part) continue;
            const m = /^(\w+):(.*)$/.exec(part);
            if (m) result.push([m[1], patternToRegex(m[2])]);
            else result.push([defaultProp || 'url', patternToRegex(part)]);
        }
        return result;
    }

    function matchProps(props, details) {
        if (props.length === 0) return true;
        for (const [k, re] of props) {
            const v = details[k];
            if (v === undefined || !re.test(String(v))) return false;
        }
        return true;
    }

    function fetchDetails(args) {
        const details = { method: 'GET' };
        const resource = args[0];
        if (resource instanceof Request) {
            details.url = resource.url;
            details.method = resource.method;
        } else {
            try { details.url = new URL(String(resource), location.href).href; } catch (e) { details.url = String(resource); }
        }
        const init = args[1];
        if (init && typeof init === 'object') {
            for (const k of ['method', 'body', 'mode', 'credentials', 'cache', 'redirect', 'referrer']) {
                if (init[k] !== undefined) details[k] = init[k];
            }
        }
        return details;
    }

    // ---------------------------------------------------------------- json prune

    function jsonPruneProps(propsToRemove) {
        return propsToRemove ? propsToRemove.split(/\s+/).filter(Boolean) : [];
    }

    /** walks path, calls fn(owner, key) for each final match */
    function walkPath(obj, path, fn) {
        const parts = path.split('.');
        const step = (o, i) => {
            if (o === null || typeof o !== 'object') return;
            const p = parts[i];
            const last = i === parts.length - 1;
            if (p === '[]' || p === '*' || p === '[-]') {
                for (const k of Object.keys(o)) {
                    if (last) fn(o, k); else step(o[k], i + 1);
                }
                return;
            }
            if (!(p in o)) return;
            if (last) fn(o, p); else step(o[p], i + 1);
        };
        step(obj, 0);
    }

    function hasPath(obj, path) {
        let found = false;
        walkPath(obj, path, () => { found = true; });
        return found;
    }

    function jsonPrune(obj, prune, required) {
        if (obj === null || typeof obj !== 'object' || prune.length === 0) return false;
        if (required.length && !required.every(p => hasPath(obj, p))) return false;
        let changed = false;
        for (const p of prune) {
            walkPath(obj, p, (o, k) => { delete o[k]; changed = true; });
        }
        return changed;
    }

    // ---------------------------------------------------------------- response hooks

    const fetchHooks = []; //each: (details, text) => text|undefined
    const xhrHooks = [];

    function installFetchHook() {
        if (installFetchHook.done || !nativeFetch) return;
        installFetchHook.done = true;
        window.fetch = new Proxy(nativeFetch, {
            apply(target, thisArg, args) {
                const details = fetchDetails(args);
                const hooks = fetchHooks.filter(h => h.match(details));
                const p = Reflect.apply(target, thisArg, args);
                if (hooks.length === 0) return p;
                return p.then(response => response.clone().text().then(text => {
                    let out = text;
                    for (const h of hooks) {
                        const r = h.transform(out, details);
                        if (r !== undefined) out = r;
                    }
                    if (out === text) return response;
                    const replaced = new Response(out, {
                        status: response.status, statusText: response.statusText, headers: response.headers
                    });
                    try {
                        Object.defineProperties(replaced, {
                            url: { value: response.url },
                            type: { value: response.type },
                            redirected: { value: response.redirected }
                        });
                    } catch (e) { }
                    return replaced;
                }).catch(() => response));
            }
        });
    }

    function installXhrHook() {
        if (installXhrHook.done) return;
        installXhrHook.done = true;
        const proto = XMLHttpRequest.prototype;
        const nativeOpen = proto.open;
        const respTextDesc = Object.getOwnPropertyDescriptor(proto, 'responseText');
        const respDesc = Object.getOwnPropertyDescriptor(proto, 'response');
        const state = new WeakMap();
        proto.open = function (method, url) {
            try {
                const details = { method: String(method).toUpperCase(), url: new URL(String(url), location.href).href };
                const hooks = xhrHooks.filter(h => h.match(details));
                if (hooks.length) state.set(this, { details, hooks, text: undefined });
                else state.delete(this);
            } catch (e) { }
            return nativeOpen.apply(this, arguments);
        };
        const transformed = (xhr) => {
            const s = state.get(xhr);
            if (!s || xhr.readyState !== 4) return undefined;
            if (s.text !== undefined) return s.text;
            const type = xhr.responseType;
            if (type !== '' && type !== 'text' && type !== 'json') return undefined;
            let text = type === 'json'
                ? nativeJSONStringify(respDesc.get.call(xhr))
                : respTextDesc.get.call(xhr);
            if (typeof text !== 'string') return undefined;
            for (const h of s.hooks) {
                const r = h.transform(text, s.details);
                if (r !== undefined) text = r;
            }
            s.text = text;
            return text;
        };
        Object.defineProperty(proto, 'responseText', {
            configurable: true, enumerable: true,
            get() {
                const t = transformed(this);
                return t !== undefined ? t : respTextDesc.get.call(this);
            }
        });
        Object.defineProperty(proto, 'response', {
            configurable: true, enumerable: true,
            get() {
                const t = transformed(this);
                if (t === undefined) return respDesc.get.call(this);
                if (this.responseType === 'json') {
                    try { return nativeJSONParse(t); } catch (e) { return null; }
                }
                return t;
            }
        });
    }

    function textReplacer(pattern, replacement) {
        const re = patternToRegex(pattern, 'gms');
        if (!re.global) {
            const flags = re.flags.includes('g') ? re.flags : re.flags + 'g';
            return text => text.replace(new RegExp(re.source, flags), replacement || '');
        }
        return text => text.replace(re, replacement || '');
    }

    function jsonTextPruner(prune, required) {
        return text => {
            let obj;
            try { obj = nativeJSONParse(text); } catch (e) { return undefined; }
            return jsonPrune(obj, prune, required) ? nativeJSONStringify(obj) : undefined;
        };
    }

    /** extra args in form `propsToMatch, value` */
    function extraArgs(args) {
        const extra = {};
        for (let i = 0; i + 1 < args.length; i += 2) extra[args[i]] = args[i + 1];
        return extra;
    }

    // ---------------------------------------------------------------- scriptlets

    function removeNodeText(nodeNamePattern, textPattern, replacement, replace) {
        const nameRe = patternToRegex(nodeNamePattern, 'i', true);
        const textRe = patternToRegex(textPattern, replace ? 'gms' : undefined);
        const handle = node => {
            if (!nameRe.test(node.nodeName)) return;
            const text = node.textContent;
            if (!text || !textRe.test(text)) return;
            textRe.lastIndex = 0;
            node.textContent = replace ? text.replace(textRe, replacement || '') : '';
        };
        const observer = new NativeMutationObserver(mutations => {
            for (const m of mutations) {
                for (const n of m.addedNodes) {
                    handle(n);
                    if (n.nodeType === 1 && n.childNodes.length) {
                        for (const c of n.childNodes) handle(c);
                    }
                }
            }
        });
        const start = () => {
            if (document.documentElement) {
                observer.observe(document, { childList: true, subtree: true });
            }
        };
        if (document.documentElement) start(); else onDomReady(start);
        //stop observing some time after page load
        window.addEventListener('load', () => nativeSetTimeout(() => observer.disconnect(), 5000), { once: true });
    }

    function setStorageItem(storage, key, raw, trusted) {
        if (!key) return;
        try {
            if (raw === '$remove$') { storage.removeItem(key); return; }
            let value;
            if (trusted) {
                value = raw.replace('$now$', String(Date.now())).replace('$currentDate$', String(new Date()))
                    .replace('$currentISODate$', new Date().toISOString());
            } else {
                const r = resolveValue(raw, false);
                if (!r) return;
                value = r.v === undefined ? 'undefined' : (typeof r.v === 'object' ? nativeJSONStringify(r.v) : String(r.v));
            }
            storage.setItem(key, value);
        } catch (e) { }
    }

    function setCookie(name, value, path, expires) {
        if (!name) return;
        let cookie = encodeURIComponent(name) + '=' + encodeURIComponent(value);
        cookie += '; path=' + (path === 'none' ? '' : (path || '/'));
        if (expires) cookie += '; expires=' + expires;
        try { document.cookie = cookie; } catch (e) { }
    }

    function removeOnDom(selector, apply) {
        runAndObserve(() => {
            for (const el of nativeQSA.call(document, selector)) apply(el);
        });
    }

    const scriptlets = {
        'set-constant'(chain, raw) {
            const r = resolveValue(raw, false);
            if (!r || !chain) return;
            trapChain(chain, (owner, prop) => defineConstant(owner, prop, r.v));
        },
        'trusted-set-constant'(chain, raw) {
            const r = resolveValue(raw, true);
            if (!r || !chain) return;
            trapChain(chain, (owner, prop) => defineConstant(owner, prop, r.v));
        },
        'abort-on-property-read'(chain) {
            if (!chain) return;
            trapChain(chain, (owner, prop) => {
                try {
                    Object.defineProperty(owner, prop, {
                        configurable: true,
                        get() { throw new ReferenceError(prop); },
                        set() { }
                    });
                } catch (e) { }
            });
        },
        'abort-on-property-write'(chain) {
            if (!chain) return;
            trapChain(chain, (owner, prop) => {
                try {
                    delete owner[prop];
                    Object.defineProperty(owner, prop, {
                        configurable: true,
                        set() { throw new ReferenceError(prop); }
                    });
                } catch (e) { }
            });
        },
        'abort-current-script'(target, needle) {
            if (!target) return;
            const re = patternToRegex(needle);
            const thisScript = document.currentScript;
            const r = resolveChain(target);
            if (!r) return;
            const [owner, prop] = r;
            let value = owner[prop];
            const desc = Object.getOwnPropertyDescriptor(owner, prop);
            if (desc && !desc.configurable) return;
            const validate = () => {
                const e = document.currentScript;
                if (!(e instanceof HTMLScriptElement) || e === thisScript) return;
                const text = e.src ? e.src : e.textContent;
                if (needle && !re.test(text)) return;
                throw new ReferenceError(prop);
            };
            try {
                Object.defineProperty(owner, prop, {
                    configurable: true,
                    get() {
                        validate();
                        return desc && desc.get ? desc.get.call(owner) : value;
                    },
                    set(v) {
                        validate();
                        if (desc && desc.set) desc.set.call(owner, v); else value = v;
                    }
                });
            } catch (e) { }
        },
        'abort-on-stack-trace'(chain, needle) {
            if (!chain) return;
            const re = patternToRegex(needle);
            trapChain(chain, (owner, prop) => {
                let value = owner[prop];
                const check = () => {
                    const stack = new Error().stack || '';
                    if (re.test(stack)) throw new ReferenceError(prop);
                };
                try {
                    Object.defineProperty(owner, prop, {
                        configurable: true,
                        get() { check(); return value; },
                        set(v) { check(); value = v; }
                    });
                } catch (e) { }
            });
        },
        'no-window-open-if'(needle) {
            const match = makeMatcher(needle);
            window.open = new Proxy(window.open, {
                apply(target, thisArg, args) {
                    const url = args[0] === undefined ? '' : String(args[0]);
                    if (!match(url)) return Reflect.apply(target, thisArg, args);
                    const fake = {
                        closed: false, opener: window, document: document.implementation.createHTMLDocument(''),
                        location: { href: url }, close() { this.closed = true; }, focus() { }, blur() { }, postMessage() { }
                    };
                    return fake;
                }
            });
        },
        'no-setTimeout-if'(needle, delay) {
            wrapTimer('setTimeout', needle, delay);
        },
        'no-setInterval-if'(needle, delay) {
            wrapTimer('setInterval', needle, delay);
        },
        'nano-setTimeout-booster'(needle, delay, boost) {
            boostTimer('setTimeout', needle, delay, boost);
        },
        'nano-setInterval-booster'(needle, delay, boost) {
            boostTimer('setInterval', needle, delay, boost);
        },
        'addEventListener-defuser'(type, needle) {
            const typeMatch = makeMatcher(type);
            const handlerMatch = makeMatcher(needle);
            const proto = EventTarget.prototype;
            proto.addEventListener = new Proxy(proto.addEventListener, {
                apply(target, thisArg, args) {
                    let handler;
                    try {
                        handler = typeof args[1] === 'function' ? String(args[1])
                            : (args[1] && typeof args[1].handleEvent === 'function' ? String(args[1].handleEvent) : String(args[1]));
                    } catch (e) {
                        handler = '';
                    }
                    if (typeMatch(String(args[0])) && handlerMatch(handler)) return;
                    return Reflect.apply(target, thisArg, args);
                }
            });
        },
        'noeval-if'(needle) {
            const re = patternToRegex(needle);
            window.eval = new Proxy(window.eval, {
                apply(target, thisArg, args) {
                    if (re.test(String(args[0]))) return;
                    return Reflect.apply(target, thisArg, args);
                }
            });
        },
        'noeval'() {
            window.eval = new Proxy(window.eval, { apply() { } });
        },
        'remove-node-text'(nodeName, pattern) {
            removeNodeText(nodeName, pattern);
        },
        'trusted-replace-node-text'(nodeName, pattern, replacement) {
            removeNodeText(nodeName, pattern, replacement, true);
        },
        'no-fetch-if'(propsSpec, responseBody) {
            if (!nativeFetch || !propsSpec) return;
            const props = parsePropsToMatch(propsSpec);
            const body = responseBody === 'emptyObj' ? '{}' : responseBody === 'emptyArr' ? '[]' : '';
            window.fetch = new Proxy(window.fetch, {
                apply(target, thisArg, args) {
                    if (!matchProps(props, fetchDetails(args))) return Reflect.apply(target, thisArg, args);
                    return Promise.resolve(new Response(body, { status: 200, statusText: 'OK' }));
                }
            });
        },
        'trusted-prevent-fetch'(propsSpec, responseBody) {
            scriptlets['no-fetch-if'](propsSpec, responseBody);
        },
        'no-xhr-if'(propsSpec, directive) {
            if (!propsSpec) return;
            const props = parsePropsToMatch(propsSpec);
            const body = directive === 'emptyObj' ? '{}' : directive === 'emptyArr' ? '[]' : '';
            const proto = XMLHttpRequest.prototype;
            const blocked = new WeakSet();
            const nativeOpen = proto.open;
            const nativeSend = proto.send;
            proto.open = function (method, url) {
                let details;
                try { details = { method: String(method).toUpperCase(), url: new URL(String(url), location.href).href }; } catch (e) { details = { method, url }; }
                if (matchProps(props, details)) blocked.add(this); else blocked.delete(this);
                return nativeOpen.apply(this, arguments);
            };
            proto.send = function () {
                if (!blocked.has(this)) return nativeSend.apply(this, arguments);
                const xhr = this;
                try {
                    Object.defineProperties(xhr, {
                        readyState: { value: 4, configurable: true },
                        status: { value: 200, configurable: true },
                        statusText: { value: 'OK', configurable: true },
                        response: { value: xhr.responseType === 'json' ? (body ? nativeJSONParse(body) : null) : body, configurable: true },
                        responseText: { value: body, configurable: true }
                    });
                } catch (e) { }
                nativeSetTimeout(() => {
                    for (const type of ['readystatechange', 'load', 'loadend']) {
                        try { xhr.dispatchEvent(new Event(type)); } catch (e) { }
                    }
                }, 1);
            };
        },
        'trusted-prevent-xhr'(propsSpec, directive) {
            scriptlets['no-xhr-if'](propsSpec, directive);
        },
        'json-prune'(propsToRemove, requiredProps) {
            const prune = jsonPruneProps(propsToRemove);
            const required = jsonPruneProps(requiredProps);
            if (prune.length === 0) return;
            JSON.parse = new Proxy(JSON.parse, {
                apply(target, thisArg, args) {
                    const obj = Reflect.apply(target, thisArg, args);
                    try { jsonPrune(obj, prune, required); } catch (e) { }
                    return obj;
                }
            });
            const nativeJson = Response.prototype.json;
            Response.prototype.json = new Proxy(nativeJson, {
                apply(target, thisArg, args) {
                    return Reflect.apply(target, thisArg, args).then(obj => {
                        try { jsonPrune(obj, prune, required); } catch (e) { }
                        return obj;
                    });
                }
            });
        },
        'json-prune-fetch-response'(propsToRemove, requiredProps, ...rest) {
            const prune = jsonPruneProps(propsToRemove);
            if (prune.length === 0) return;
            const extra = extraArgs(rest);
            const props = parsePropsToMatch(extra.propsToMatch);
            fetchHooks.push({ match: d => matchProps(props, d), transform: jsonTextPruner(prune, jsonPruneProps(requiredProps)) });
            installFetchHook();
        },
        'json-prune-xhr-response'(propsToRemove, requiredProps, ...rest) {
            const prune = jsonPruneProps(propsToRemove);
            if (prune.length === 0) return;
            const extra = extraArgs(rest);
            const props = parsePropsToMatch(extra.propsToMatch);
            xhrHooks.push({ match: d => matchProps(props, d), transform: jsonTextPruner(prune, jsonPruneProps(requiredProps)) });
            installXhrHook();
        },
        'trusted-replace-fetch-response'(pattern, replacement, propsSpec) {
            if (!pattern) return;
            const props = parsePropsToMatch(propsSpec);
            const replace = textReplacer(pattern, replacement);
            fetchHooks.push({ match: d => matchProps(props, d), transform: replace });
            installFetchHook();
        },
        'trusted-replace-xhr-response'(pattern, replacement, propsSpec) {
            if (!pattern) return;
            const props = parsePropsToMatch(propsSpec);
            const replace = textReplacer(pattern, replacement);
            xhrHooks.push({ match: d => matchProps(props, d), transform: replace });
            installXhrHook();
        },
        'remove-attr'(attrs, selector) {
            if (!attrs) return;
            const list = attrs.split(/\s*\|\s*/).filter(Boolean);
            const sel = selector || list.map(a => '[' + a + ']').join(',');
            removeOnDom(sel, el => { for (const a of list) el.removeAttribute(a); });
        },
        'remove-class'(classes, selector) {
            if (!classes) return;
            const list = classes.split(/\s*\|\s*/).filter(Boolean);
            const sel = selector || list.map(c => '.' + CSS.escape(c)).join(',');
            removeOnDom(sel, el => el.classList.remove(...list));
        },
        'set-attr'(selector, attr, value) {
            if (!selector || !attr || attr.toLowerCase().startsWith('on')) return;
            if (value !== undefined && !/^(?:\d+|true|false|)$/.test(value) && !/^\[.+\]$/.test(value)) return;
            removeOnDom(selector, el => el.setAttribute(attr, value || ''));
        },
        'trusted-set-attr'(selector, attr, value) {
            if (!selector || !attr) return;
            removeOnDom(selector, el => el.setAttribute(attr, value || ''));
        },
        'set-cookie'(name, value, path) {
            const allowed = /^(?:true|false|yes|y|no|n|ok|on|off|accept|accepted|notaccepted|reject|rejected|allow|allowed|disallow|deny|denied|enable|enabled|disable|disabled|necessary|required|hide|hidden|essential|nonessential|checked|unchecked|forbidden|forever|-?\d+|)$/i;
            if (!allowed.test(value || '')) return;
            setCookie(name, value || '', path);
        },
        'trusted-set-cookie'(name, value, offsetExpiresSec, path) {
            const v = (value || '').replace('$now$', String(Date.now())).replace('$currentDate$', new Date().toUTCString())
                .replace('$currentISODate$', new Date().toISOString());
            let expires;
            if (offsetExpiresSec) {
                const days = { '1day': 86400, '1year': 31536000 }[offsetExpiresSec];
                const sec = days || parseInt(offsetExpiresSec, 10);
                if (!isNaN(sec)) expires = new Date(Date.now() + sec * 1000).toUTCString();
            }
            setCookie(name, v, path, expires);
        },
        'remove-cookie'(needle) {
            const re = patternToRegex(needle);
            const remove = () => {
                for (const cookie of document.cookie.split(';')) {
                    const name = cookie.split('=')[0].trim();
                    if (!name || !re.test(name)) continue;
                    const expired = name + '=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
                    const host = location.hostname;
                    document.cookie = expired;
                    document.cookie = expired + '; path=/';
                    document.cookie = expired + '; path=/; domain=' + host;
                    document.cookie = expired + '; path=/; domain=.' + host.split('.').slice(-2).join('.');
                }
            };
            remove();
            window.addEventListener('beforeunload', remove);
        },
        'set-local-storage-item'(key, value) { setStorageItem(localStorage, key, value, false); },
        'trusted-set-local-storage-item'(key, value) { setStorageItem(localStorage, key, value, true); },
        'set-session-storage-item'(key, value) { setStorageItem(sessionStorage, key, value, false); },
        'trusted-set-session-storage-item'(key, value) { setStorageItem(sessionStorage, key, value, true); }
    };

    function defineConstant(owner, prop, value) {
        const desc = Object.getOwnPropertyDescriptor(owner, prop);
        if (desc && !desc.configurable) {
            try { owner[prop] = value; } catch (e) { }
            return;
        }
        try {
            Object.defineProperty(owner, prop, {
                configurable: true,
                enumerable: desc ? desc.enumerable : true,
                get() { return value; },
                set() { }
            });
        } catch (e) { }
    }

    function wrapTimer(name, needle, delayArg) {
        const match = makeMatcher(needle);
        let delayMatch = () => true;
        if (delayArg) {
            let negate = false;
            let d = delayArg;
            if (d.startsWith('!')) { negate = true; d = d.slice(1); }
            const n = parseInt(d, 10);
            if (!isNaN(n)) delayMatch = v => (v === n) !== negate;
        }
        window[name] = new Proxy(window[name], {
            apply(target, thisArg, args) {
                const code = typeof args[0] === 'function' ? Function.prototype.toString.call(args[0]) : String(args[0]);
                if (match(code) && delayMatch(Number(args[1]) || 0)) args[0] = noopFunc;
                return Reflect.apply(target, thisArg, args);
            }
        });
    }

    function boostTimer(name, needle, delayArg, boostArg) {
        const re = patternToRegex(needle);
        const delay = delayArg === undefined || delayArg === '' ? 1000 : (delayArg === '*' ? -1 : parseInt(delayArg, 10));
        let boost = parseFloat(boostArg);
        if (isNaN(boost) || !isFinite(boost)) boost = 0.05;
        boost = Math.min(50, Math.max(0.001, boost));
        window[name] = new Proxy(window[name], {
            apply(target, thisArg, args) {
                const code = typeof args[0] === 'function' ? Function.prototype.toString.call(args[0]) : String(args[0]);
                const d = Number(args[1]) || 0;
                if ((delay === -1 || d === delay) && re.test(code)) args[1] = d * boost;
                return Reflect.apply(target, thisArg, args);
            }
        });
    }

    for (const item of data.scriptlets) {
        const fn = scriptlets[item[0]];
        if (!fn) continue;
        try { fn.apply(null, item.slice(1)); } catch (e) { }
    }

    // ---------------------------------------------------------------- cosmetic filtering

    addStyle(data.css);

    function applyProcedural(task, roots) {
        let nodes;
        if (task.s) {
            nodes = [];
            for (const root of roots) {
                let sel = task.s;
                if (root !== document && /^[>+~]/.test(sel)) sel = ':scope ' + sel;
                try {
                    for (const n of (root === document ? nativeQSA.call(document, sel) : nativeElQSA.call(root, sel))) nodes.push(n);
                } catch (e) {
                    return [];
                }
            }
        } else {
            nodes = roots === undefined ? [document.documentElement] : roots.filter(r => r !== document);
            if (roots.length === 1 && roots[0] === document) nodes = [document.documentElement];
        }
        for (const [op, arg] of task.t) {
            if (nodes.length === 0) break;
            nodes = applyOperator(op, arg, nodes);
        }
        return Array.from(new Set(nodes));
    }

    function textMatcher(arg) {
        const re = patternToRegex(arg);
        return el => re.test(el.textContent);
    }

    function applyOperator(op, arg, nodes) {
        switch (op) {
            case 'has-text': {
                const m = textMatcher(arg);
                return nodes.filter(m);
            }
            case 'min-text-length': {
                const n = parseInt(arg, 10);
                return nodes.filter(el => el.textContent.length >= n);
            }
            case 'upward': {
                const out = [];
                const n = parseInt(arg, 10);
                for (const el of nodes) {
                    let p = el;
                    if (!isNaN(n) && String(n) === arg.trim()) {
                        for (let i = 0; i < n && p; i++) p = p.parentElement;
                    } else {
                        p = el.parentElement ? nativeClosest.call(el.parentElement, arg) : null;
                    }
                    if (p && p !== document.documentElement && p !== document.body) out.push(p);
                }
                return out;
            }
            case 'xpath': {
                const out = [];
                for (const el of nodes) {
                    try {
                        const r = document.evaluate(arg, el, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
                        for (let i = 0; i < r.snapshotLength; i++) if (r.snapshotItem(i).nodeType === 1) out.push(r.snapshotItem(i));
                    } catch (e) { }
                }
                return out;
            }
            case 'matches-css':
            case 'matches-css-before':
            case 'matches-css-after': {
                const pseudo = op === 'matches-css-before' ? '::before' : op === 'matches-css-after' ? '::after' : null;
                const pos = arg.indexOf(':');
                if (pos < 0) return [];
                const prop = arg.slice(0, pos).trim();
                const re = patternToRegex(arg.slice(pos + 1).trim(), undefined, true);
                return nodes.filter(el => re.test(getComputedStyle(el, pseudo).getPropertyValue(prop)));
            }
            case 'matches-path': {
                const m = makeMatcher(arg);
                return m(location.pathname + location.search) ? nodes : [];
            }
            case 'matches-attr': {
                const m = /^\s*"?([^"=]+)"?\s*(?:=\s*"?(.*?)"?)?\s*$/.exec(arg);
                if (!m) return [];
                const nameRe = patternToRegex(m[1], undefined, true);
                const valueRe = patternToRegex(m[2] || '', undefined, true);
                return nodes.filter(el => {
                    for (const a of el.attributes) if (nameRe.test(a.name) && (m[2] === undefined || valueRe.test(a.value))) return true;
                    return false;
                });
            }
            case 'matches-media':
                return window.matchMedia(arg).matches ? nodes : [];
            case 'has':
                return nodes.filter(el => applyProcedural(arg, [el]).length > 0);
            case 'not':
                return nodes.filter(el => applyProcedural(arg, [el]).length === 0);
            case 'spath': {
                const out = [];
                const sel = arg.trim();
                for (const el of nodes) {
                    try {
                        if (sel[0] === '+' || sel[0] === '~') {
                            const rest = sel.slice(1).trim();
                            for (let n = el.nextElementSibling; n; n = n.nextElementSibling) {
                                if (nativeMatches.call(n, rest)) out.push(n);
                                if (sel[0] === '+') break;
                            }
                        } else if (sel[0] === '>' || /^\s/.test(arg)) {
                            for (const n of nativeElQSA.call(el, ':scope ' + sel)) out.push(n);
                        } else if (nativeMatches.call(el, sel)) {
                            out.push(el);
                        }
                    } catch (e) { }
                }
                return out;
            }
            default:
                //others, watch-attr: not supported, keep nodes as is
                return nodes;
        }
    }

    function applyAction(task, el) {
        const a = task.a;
        if (!a) {
            el.style.setProperty('display', 'none', 'important');
            return;
        }
        switch (a[0]) {
            case 'remove': el.remove(); break;
            case 'remove-attr': {
                const re = patternToRegex(a[1], undefined, true);
                for (const attr of Array.from(el.attributes)) if (re.test(attr.name)) el.removeAttribute(attr.name);
                break;
            }
            case 'remove-class': {
                const re = patternToRegex(a[1], undefined, true);
                for (const c of Array.from(el.classList)) if (re.test(c)) el.classList.remove(c);
                break;
            }
            case 'style': el.style.cssText += ';' + a[1]; break;
        }
    }

    if (data.procedural.length) {
        runAndObserve(() => {
            for (const task of data.procedural) {
                for (const el of applyProcedural(task, [document])) applyAction(task, el);
            }
        });
    }

    //generic cosmetic filters keyed by ids and classes present on the page
    if (data.generic) {
        const seen = new Set();
        let pending = [];
        let timer;
        let genericStyle = null;
        const flush = () => {
            timer = undefined;
            if (pending.length === 0) return;
            const keys = pending;
            pending = [];
            let css;
            try { css = bridge.getGenericCss(location.href, nativeJSONStringify(keys)); } catch (e) { return; }
            if (!css) return;
            if (genericStyle && genericStyle.isConnected) genericStyle.textContent += css;
            else {
                genericStyle = document.createElement('style');
                genericStyle.textContent = css;
                (document.head || document.documentElement).appendChild(genericStyle);
            }
        };
        const collect = el => {
            const id = el.id;
            if (id && typeof id === 'string') {
                const k = '#' + id;
                if (!seen.has(k)) { seen.add(k); pending.push(k); }
            }
            const cl = el.classList;
            if (cl) for (const c of cl) {
                const k = '.' + c;
                if (!seen.has(k)) { seen.add(k); pending.push(k); }
            }
        };
        const scanAll = () => {
            for (const el of nativeQSA.call(document, '[id],[class]')) collect(el);
            flush();
        };
        const observer = new NativeMutationObserver(mutations => {
            for (const m of mutations) {
                if (m.type === 'attributes') { collect(m.target); continue; }
                for (const n of m.addedNodes) {
                    if (n.nodeType !== 1) continue;
                    collect(n);
                    if (n.firstElementChild) for (const c of nativeElQSA.call(n, '[id],[class]')) collect(c);
                }
            }
            if (pending.length && timer === undefined) timer = nativeSetTimeout(flush, 30);
        });
        const start = () => {
            observer.observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['id', 'class'] });
        };
        if (document.documentElement) start(); else onDomReady(start);
        onDomReady(scanAll);
    }
})();

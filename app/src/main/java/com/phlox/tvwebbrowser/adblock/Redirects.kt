package com.phlox.tvwebbrowser.adblock

import android.util.Base64
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** Neutered resources served instead of blocked ones for `$redirect=` filters (subset of uBO's resources) */
object Redirects {
    private const val GIF_1X1 = "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
    private const val PNG_1X1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

    private const val GA_STUB = """(function(){var noop=function(){};var ga=function(){var a=arguments[arguments.length-1];
if(a&&typeof a==='object'&&typeof a.hitCallback==='function'){try{a.hitCallback()}catch(e){}}};
var tracker={get:noop,set:noop,send:noop};ga.create=function(){return tracker};ga.getByName=function(){return tracker};
ga.getAll=function(){return [tracker]};ga.remove=noop;ga.loaded=true;var name=window.GoogleAnalyticsObject||'ga';
var q=window[name]&&window[name].q;window[name]=ga;if(Array.isArray(q)){q.forEach(function(args){try{ga.apply(null,args)}catch(e){}})}})();"""

    private const val GTM_STUB = """(function(){var dl=window.dataLayer;if(dl&&typeof dl.push==='function'){
var call=function(e){if(e&&typeof e.eventCallback==='function'){try{setTimeout(e.eventCallback,1)}catch(x){}}};
if(Array.isArray(dl)){dl.forEach(call)}var push=dl.push;dl.push=function(e){call(e);return push.apply(dl,arguments)}}})();"""

    private const val ADSBYGOOGLE_STUB = """(function(){window.adsbygoogle={loaded:true,push:function(){}};
var p=document.querySelectorAll('.adsbygoogle');for(var i=0;i<p.length;i++){p[i].style.setProperty('display','none','important')}})();"""

    private const val GPT_STUB = """(function(){var noop=function(){};var slot={};['addService','setTargeting','defineSizeMapping',
'setCollapseEmptyDiv','clearTargeting','setClickUrl','set','setForceSafeFrame','setSafeFrameConfig','updateTargetingFromMap'].forEach(function(k){slot[k]=function(){return slot}});
slot.getSlotElementId=function(){return ''};slot.getTargeting=function(){return []};slot.getTargetingKeys=function(){return []};
slot.getAdUnitPath=function(){return ''};slot.getSizes=function(){return []};slot.getResponseInformation=function(){return null};
var pubads={};['addEventListener','removeEventListener','setTargeting','clearTargeting','setRequestNonPersonalizedAds',
'setPrivacySettings','set','setCentering','enableSingleRequest','enableLazyLoad','collapseEmptyDivs','disableInitialLoad',
'enableAsyncRendering','enableVideoAds','setForceSafeFrame','setSafeFrameConfig','setPublisherProvidedId','setLocation'].forEach(function(k){pubads[k]=function(){return pubads}});
pubads.refresh=noop;pubads.getSlots=function(){return []};pubads.getTargeting=function(){return []};pubads.getTargetingKeys=function(){return []};
pubads.updateCorrelator=noop;pubads.display=noop;pubads.clear=noop;
var gt=window.googletag||{};var cmd=gt.cmd||[];gt.apiReady=true;gt.pubadsReady=true;
gt.cmd={push:function(f){try{f()}catch(e){}return 1}};gt.pubads=function(){return pubads};gt.defineSlot=function(){return slot};
gt.defineOutOfPageSlot=function(){return slot};gt.enableServices=noop;gt.display=noop;gt.destroySlots=noop;gt.disablePublisherConsole=noop;
gt.companionAds=function(){return {setRefreshUnfilledSlots:noop,addEventListener:noop}};gt.content=function(){return {setContent:noop}};
gt.sizeMapping=function(){var b={addSize:function(){return b},build:function(){return []}};return b};gt.setConfig=noop;gt.getVersion=function(){return ''};
window.googletag=gt;if(Array.isArray(cmd)){cmd.forEach(function(f){try{f()}catch(e){}})}})();"""

    private const val APSTAG_STUB = """(function(){window.apstag={init:function(){},fetchBids:function(a,cb){if(typeof cb==='function'){setTimeout(function(){cb([])},1)}},
setDisplayBids:function(){},targetingKeys:function(){return []},_getSlotIdToNameMapping:function(){}};})();"""

    private const val FINGERPRINT2_STUB = """(function(){var cb=function(o,f){if(typeof o==='function')f=o;setTimeout(function(){if(f)f('',[])},1)};
var F=function(){};F.get=cb;F.getV18=cb;F.getPromise=function(){return Promise.resolve([])};F.x64hash128=function(){return ''};
F.prototype={get:cb};window.Fingerprint2=F;})();"""

    private const val PREBID_ADS_STUB = "window.canRunAds=true;window.isAdBlockActive=false;"

    private val SCRIPTS = mapOf(
        "google-analytics_analytics.js" to GA_STUB, "google-analytics.com/analytics.js" to GA_STUB,
        "googletagmanager_gtm.js" to GTM_STUB, "googletagmanager.com/gtm.js" to GTM_STUB,
        "googlesyndication_adsbygoogle.js" to ADSBYGOOGLE_STUB, "googlesyndication.com/adsbygoogle.js" to ADSBYGOOGLE_STUB,
        "googletagservices_gpt.js" to GPT_STUB, "googletagservices.com/gpt.js" to GPT_STUB,
        "amazon_apstag.js" to APSTAG_STUB,
        "fingerprint2.js" to FINGERPRINT2_STUB,
        "prebid-ads.js" to PREBID_ADS_STUB
    )

    private fun response(mime: String, data: ByteArray): WebResourceResponse {
        val r = WebResourceResponse(mime, "utf-8", ByteArrayInputStream(data))
        r.responseHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache")
        return r
    }

    private fun text(mime: String, s: String) = response(mime, s.toByteArray())

    /** Response for blocked request: redirect resource or an error-like empty response */
    fun response(redirect: String?, type: Int): WebResourceResponse {
        if (redirect != null) {
            SCRIPTS[redirect]?.let { return text("application/javascript", it) }
            when (redirect) {
                "noopjs", "noop.js", "google-ima.js", "google-ima3", "chartbeat.js", "nobab2.js", "nobab.js",
                "fuckadblock.js-3.2.0", "nofab.js", "popads.js", "popads-dummy.js", "ampproject_v0.js",
                "outbrain-widget.js", "scorecardresearch_beacon.js", "doubleclick_instream_ad_status.js" ->
                    return text("application/javascript", "")
                "1x1.gif", "1x1-transparent.gif" -> return response("image/gif", Base64.decode(GIF_1X1, Base64.DEFAULT))
                "2x2.png", "3x2.png", "32x32.png", "2x2-transparent.png", "3x2-transparent.png", "32x32-transparent.png" ->
                    return response("image/png", Base64.decode(PNG_1X1, Base64.DEFAULT))
                "noopframe", "noop.html" -> return text("text/html", "<!DOCTYPE html><html><head></head><body></body></html>")
                "noop.css" -> return text("text/css", "")
                "noopvast-2.0" -> return text("application/xml", "<VAST version=\"2.0\"></VAST>")
                "noopvast-3.0" -> return text("application/xml", "<VAST version=\"3.0\"></VAST>")
                "noopvast-4.0" -> return text("application/xml", "<VAST version=\"4.0\"></VAST>")
                "noopmp3-0.1s", "noop-0.1s.mp3" -> return response("audio/mpeg", ByteArray(0))
                "noopmp4-1s", "noop-1s.mp4" -> return response("video/mp4", ByteArray(0))
                "nooptext", "noop.txt", "empty", "none" -> return text("text/plain", "")
            }
            //unknown resource - serve empty content of appropriate type
            return when (type) {
                RequestType.SCRIPT -> text("application/javascript", "")
                RequestType.IMAGE -> response("image/gif", Base64.decode(GIF_1X1, Base64.DEFAULT))
                else -> text("text/plain", "")
            }
        }
        val r = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        r.setStatusCodeAndReasonPhrase(403, "Blocked")
        return r
    }
}

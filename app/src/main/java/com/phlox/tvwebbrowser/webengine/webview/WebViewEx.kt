package com.phlox.tvwebbrowser.webengine.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaDrm
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebBackForwardList
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import java.io.ByteArrayInputStream
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.adblock.WebViewAdBlock
import com.phlox.tvwebbrowser.webengine.MediaTools
import com.phlox.tvwebbrowser.webengine.LoadErrorKind
import com.phlox.tvwebbrowser.webengine.LoadErrorPage
import com.phlox.tvwebbrowser.webengine.MediaState
import com.phlox.tvwebbrowser.webengine.MediaCommand
import com.phlox.tvwebbrowser.webengine.PageVideo
import com.phlox.tvwebbrowser.webengine.ContentFilter
import com.phlox.tvwebbrowser.utils.DPADNavigationEventsAdapter
import com.phlox.tvwebbrowser.utils.Utils
import java.net.URLEncoder
import java.util.UUID


/**
 * Copyright (c) 2016 Fedir Tsapana.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
open class WebViewEx(context: Context, val callback: Callback, val jsInterface: AndroidJSInterface) : WebView(context) {
    companion object {
        val TAG = WebViewEx::class.java.simpleName
        const val WEB_VIEW_TAG = "TV Bro WebView"
        const val INTERNAL_SCHEME = "internal://"
        private const val BLOCKED_PAGE = "<html><body style=\"background:#111;color:#ccc;font:24px sans-serif;" +
                "text-align:center;padding-top:20%\">&#128737; TV Akhi blocked this site</body></html>"
        const val INTERNAL_SCHEME_WARNING_DOMAIN = "warning"
        const val INTERNAL_SCHEME_WARNING_DOMAIN_TYPE_CERT = "certificate"
        val WIDEVINE_UUID = UUID(-0x121074568629b532L,-0x5c37d8232ae2de13L)
    }

    private var virtualCursorMode: Boolean = true
    private var genericInjects: String? = null
    private var webChromeClient_: WebChromeClient
    private var fullscreenViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pickFileCallback: ValueCallback<Array<Uri>>? = null
    private var permRequestDialog: AlertDialog? = null
    private var geoPermissionOrigin: String? = null
    private var geoPermissionsCallback: GeolocationPermissions.Callback? = null
    var lastSSLError: SslError? = null
    var trustSsl: Boolean = false
    var currentOriginalUrl: Uri? = null
    @Volatile private var adBlockPageUrl: String? = null
    @Volatile private var adBlockPageFlagsCache: Pair<String, Int>? = null
    private val documentStartScriptSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    private val uiHandler = Handler(Looper.getMainLooper())
    private val mediaControls = WebViewMediaControls(this, { callback.onMediaStateChanged(it) },
        { callback.onPageVideosChanged(it) }, { callback.onMediaVideoChanged(it) })
    //the error page for the page that failed to load, shown when WebView finished its own one
    private var pendingErrorPage: PendingErrorPage? = null
    //a Safe Browsing hit of a main frame: its url and threat type, for the error page that follows
    private var safeBrowsingThreat: Pair<String, Int>? = null
    private val pagePermissions = WebViewPagePermissions(object : WebViewPagePermissions.Host {
        override fun hasAndroidPermission(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        override fun requestAndroidPermissions(permissions: Array<String>) {
            callback.requestPermissions(permissions, false)
        }

        override fun showDialog(resources: Array<String>, onAnswer: (Boolean) -> Unit): (() -> Unit)? {
            val activity = callback.getActivity() ?: return null
            val dialog = AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.web_perm_request_confirmation, TextUtils.join("\n", resources)))
                .setCancelable(false)
                .setNegativeButton(R.string.deny) { _, _ -> onAnswer(false) }
                .setPositiveButton(R.string.allow) { _, _ -> onAnswer(true) }
                .create()
            dialog.show()
            return dialog::dismiss
        }
    })
    private val config = AppContext.provideConfig()

    interface Callback {
        fun getActivity(): Activity?
        /** The page process of WebView crashed or was killed (low memory): this view can not be used anymore */
        fun onRenderProcessGone(didCrash: Boolean)
        /** The media the keys of the remote control changed (null: the page has none) */
        fun onMediaStateChanged(state: MediaState?)
        /** The videos of the page other players can play changed */
        fun onPageVideosChanged(videos: List<PageVideo>)
        /** The video among them that the media the keys control plays (null: none known) */
        fun onMediaVideoChanged(video: PageVideo?)
        fun onOpenInNewTabRequested(url: String)
        fun onDownloadRequested(url: String)
        fun onThumbnailError()
        fun onShowCustomView(view: View)
        fun onHideCustomView()
        fun onProgressChanged(newProgress: Int)
        fun onReceivedTitle(title: String)
        fun onShowFileChooser(intent: Intent): Boolean
        fun onReceivedIcon(icon: Bitmap)
        fun requestPermissions(array: Array<String>, geo: Boolean)
        fun shouldOverrideUrlLoading(url: String): Boolean
        fun onPageStarted(url: String?)
        fun onPageFinished(url: String?)
        fun onPageCertificateError(url: String?)
        fun isAdBlockingEnabled(): Boolean
        fun isDialogsBlockingEnabled(): Boolean
        fun onBlockedAd(url: Uri)
        fun onBlockedDialog(newTab: Boolean)
        fun onCreateWindow(dialog: Boolean, userGesture: Boolean): WebViewEx?
        fun closeWindow(window: WebView)
        fun onDownloadStart(url: String, userAgent: String, contentDisposition: String, mimetype: String?, contentLength: Long)
        fun onScaleChanged(oldScale: Float, newScale: Float)
        fun onCopyTextToClipboardRequested(url: String)
        fun onShareUrlRequested(url: String)
        fun onOpenInExternalAppRequested(url: String)
        fun onVisited(url: String)
        fun onContextMenu(baseUrl: String?, href: String?, x: Int, y: Int)
    }

    init {
        with(settings) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = callback.isAdBlockingEnabled()
            }
            javaScriptEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            domStorageEnabled = true
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = !config.allowAutoplayMedia
            setGeolocationEnabled(true)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            setNeedInitialFocus(false)

            domStorageEnabled = true
            if (config.webEngineDebug) {
                setWebContentsDebuggingEnabled(true)
            }

            val allowDarkening = config.webviewUseAlgorithmicDarkeningWithDarkUiMode
            val uiNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    if (uiNightMode == Configuration.UI_MODE_NIGHT_YES && allowDarkening) {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, true)
                    } else {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, false)
                    }
                }
            } else {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                    if (uiNightMode == Configuration.UI_MODE_NIGHT_YES && allowDarkening) {
                        WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_ON)
                    } else {
                        WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_OFF)
                    }
                }
            }
        }

        setOnLongClickListener { v ->
            true
        }

        webChromeClient_ = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                return if (callback.isDialogsBlockingEnabled()) {
                    callback.onBlockedDialog(false)
                    result.cancel()
                    true
                } else super.onJsAlert(view, url, message, result)
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                return if (callback.isDialogsBlockingEnabled()) {
                    callback.onBlockedDialog(false)
                    result.cancel()
                    true
                } else super.onJsConfirm(view, url, message, result)
            }

            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean {
                return if (callback.isDialogsBlockingEnabled()) {
                    callback.onBlockedDialog(false)
                    result.cancel()
                    true
                } else super.onJsPrompt(view, url, message, defaultValue, result)
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                this@WebViewEx.callback.onShowCustomView(view)
                fullscreenViewCallback = callback
            }

            override fun onHideCustomView() {
                callback.onHideCustomView()
                fullscreenViewCallback?.onCustomViewHidden()
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                callback.onProgressChanged(newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String) {
                callback.onReceivedTitle(title)
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                if (request.resources.size == 1 &&
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID == request.resources[0]) {
                    //fast path for grant/deny RESOURCE_PROTECTED_MEDIA_ID
                    if (MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID)) {
                        val widevineKeyDrm = MediaDrm(WIDEVINE_UUID)
                        val version = widevineKeyDrm.getPropertyString(MediaDrm.PROPERTY_VERSION)
                        Log.i(TAG, "DRM widevine version = " + version)
                        request.grant(request.resources)
                    } else {
                        request.deny()
                    }
                    return
                }

                pagePermissions.onRequest(request)
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                pagePermissions.onRequestCanceled(request)
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                val activity = this@WebViewEx.callback.getActivity() ?: return
                geoPermissionOrigin = origin
                geoPermissionsCallback = callback
                permRequestDialog = AlertDialog.Builder(activity)
                        .setMessage(activity.getString(R.string.web_perm_request_confirmation, activity.getString(R.string.location)))
                        .setCancelable(false)
                        .setNegativeButton(R.string.deny) { dialog, which ->
                            geoPermissionsCallback!!.invoke(geoPermissionOrigin, false, false)
                            permRequestDialog = null
                            geoPermissionsCallback = null
                        }
                        .setPositiveButton(R.string.allow) { dialog, which ->
                            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                this@WebViewEx.callback.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), true)
                            } else {
                                geoPermissionsCallback!!.invoke(geoPermissionOrigin, true, true)
                                geoPermissionsCallback = null
                            }
                            permRequestDialog = null
                        }
                        .create()
                permRequestDialog!!.show()
            }

            override fun onGeolocationPermissionsHidePrompt() {
                if (permRequestDialog != null) {
                    permRequestDialog!!.dismiss()
                    permRequestDialog = null
                }
                geoPermissionsCallback = null
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val msg: String = "(" + consoleMessage.sourceId() + "[" + consoleMessage.lineNumber() + "]): " + consoleMessage.message()
                when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.e(WEB_VIEW_TAG, msg)
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(WEB_VIEW_TAG, msg)
                    else -> Log.i(WEB_VIEW_TAG, msg)
                }
                return true
            }


            override fun onShowFileChooser(mWebView: WebView, callback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean {
                pickFileCallback = callback

                val result = this@WebViewEx.callback.onShowFileChooser(fileChooserParams.createIntent())
                if (!result) {
                    pickFileCallback = null
                }
                return result
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap) {
                Log.d(TAG, "onReceivedIcon: ${icon.width}x${icon.height}")
                callback.onReceivedIcon(icon)
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (config.openNewWindowsInCurrentTab && !isDialog) {
                    //catch the new window's first url with a throwaway WebView and load it in this one
                    val catcher = WebView(view.context)
                    catcher.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url.toString()
                            v.post { v.destroy() }
                            //a pop-up to a blocked site (mostly the ad a player opens on the first click): dropped,
                            //the page (and its video) stays instead of turning into the blocked page
                            //the same for ad pop-ups, or the ad (or the ad blocker's "Page blocked") replaces the video
                            val openerUrl = currentOriginalUrl?.toString()
                            if (ContentFilter.categoryOfUrl(url) != null || (callback.isAdBlockingEnabled() &&
                                    WebViewAdBlock.isBlockedPopup(url, openerUrl, adBlockPageFlags(openerUrl)))) {
                                Log.d(TAG, "Blocked pop-up: $url")
                                callback.onBlockedDialog(true)
                                return true
                            }
                            this@WebViewEx.loadUrl(url)
                            return true
                        }
                    }
                    (resultMsg.obj as WebView.WebViewTransport).webView = catcher
                    resultMsg.sendToTarget()
                    return true
                }
                val webView = callback.onCreateWindow(isDialog, isUserGesture) ?: return false
                (resultMsg.obj as WebView.WebViewTransport).webView = webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                callback.closeWindow(window)
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                Log.d(TAG, "shouldOverrideUrlLoading url: ${request.url}")
                return callback.shouldOverrideUrlLoading(request.url.toString())
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                Log.d(TAG, "shouldInterceptRequest url: ${request.url}")
                val currentPageUrl = currentOriginalUrl

                if (currentPageUrl != null && currentPageUrl.toString().startsWith(Config.HOME_PAGE_URL,
                        ignoreCase = true)) {
                    HomePageHelper.shouldInterceptRequest(view, request)?.let {
                        return it
                    }
                    if (request.url.toString().startsWith(Config.HOME_PAGE_URL)) {
                        var relativePath = request.url.toString().substring(Config.HOME_PAGE_URL.length)
                        if (relativePath.isEmpty() || relativePath == "/") {
                            relativePath = "index.html"
                        }
                        val assetsPath = "pages/home/$relativePath"
                        val response = Utils.getWebResourceResponseFromAssets(view.context, assetsPath)
                        if (response != null) {
                            Log.d(TAG, "shouldInterceptRequest url: ${request.url} -> $assetsPath")
                            return response
                        } else {
                            Log.w(TAG, "shouldInterceptRequest url: ${request.url} -> not found in assets")
                        }
                        return response ?: super.shouldInterceptRequest(view, request)
                    }
                }

                //main pages: the search too (forms and in-page searches don't always reach shouldOverrideUrlLoading)
                val blocked = if (request.isForMainFrame) ContentFilter.categoryOfUrl(request.url.toString())
                    else ContentFilter.blockedCategory(request.url.host)
                if (blocked != null) {
                    Log.d(TAG, "Blocked by the content filter: ${request.url}")
                    val page = if (request.isForMainFrame) BLOCKED_PAGE.toByteArray() else ByteArray(0)
                    return WebResourceResponse("text/html", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(page))
                }

                if (callback.isAdBlockingEnabled()) {
                    if (request.isForMainFrame) adBlockPageUrl = request.url.toString()
                    val pageUrl = adBlockPageUrl ?: currentPageUrl?.toString()
                    WebViewAdBlock.interceptRequest(request, pageUrl, adBlockPageFlags(pageUrl))?.let {
                        Log.d(TAG, "Blocked: ${request.url}")
                        uiHandler.post { callback.onBlockedAd(request.url) }
                        return it
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "onPageStarted url: $url")
                if (pendingErrorPage?.url != url) pendingErrorPage = null
                currentOriginalUrl = url.toUri()
                mediaControls.onPageStarted()
                adBlockPageUrl = url
                if (!documentStartScriptSupported && callback.isAdBlockingEnabled()) {
                    evaluateJavascript(WebViewAdBlock.contentScript(context), null)
                }
                callback.onPageStarted(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "onPageFinished url: $url")
                val errorPage = pendingErrorPage
                if (errorPage != null) {
                    pendingErrorPage = null
                    if (errorPage.url == url) showErrorPage(errorPage)
                }
                callback.onPageFinished(url)
                evaluateJavascript(getGenericJSInjects(), null)
                mediaControls.onPageFinished()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (!request.isForMainFrame) return
                val url = request.url.toString()
                Log.d(TAG, "onReceivedError url: $url code: ${error.errorCode} ${error.description}")
                //the certificate warning is shown instead
                if (lastSSLError?.url == url) return
                val kind = WebViewLoadErrors.kind(error.errorCode)
                val threat = safeBrowsingThreat?.takeIf { it.first == url }
                safeBrowsingThreat = null
                val errorCode = if (error.errorCode == ERROR_UNSAFE_RESOURCE && threat != null) {
                    WebViewLoadErrors.safeBrowsingThreatName(threat.second)
                } else {
                    error.description?.toString() ?: ""
                }
                pendingErrorPage = PendingErrorPage(url, LoadErrorPage.create(context, kind, url, errorCode))
            }

            @RequiresApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.e(TAG, "onRenderProcessGone crash: ${detail.didCrash()}")
                //the engine drops this view; returning false would end the app
                callback.onRenderProcessGone(detail.didCrash())
                return true
            }

            @RequiresApi(Build.VERSION_CODES.O_MR1)
            override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int,
                                           callback: SafeBrowsingResponse) {
                Log.w(TAG, "onSafeBrowsingHit url: ${request.url} threat: $threatType main frame: ${request.isForMainFrame}")
                if (request.isForMainFrame) {
                    //instead of the warning of WebView: the load fails with ERROR_UNSAFE_RESOURCE, and
                    //onReceivedError shows the page of a dangerous site
                    safeBrowsingThreat = request.url.toString() to threatType
                    callback.backToSafety(false)
                } else {
                    super.onSafeBrowsingHit(view, request, threatType, callback)
                }
            }

            override fun onLoadResource(view: WebView, url: String) {
                super.onLoadResource(view, url)
                //Log.d(TAG, "onLoadResource url: $url")
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                Log.e(TAG, "onReceivedSslError url: ${error.url}")
                if (trustSsl && lastSSLError?.certificate?.toString()?.equals(error.certificate.toString()) == true) {
                    trustSsl = false
                    lastSSLError = null
                    handler.proceed()
                    return
                }
                handler.cancel()
                val errUrl = error.url ?: return
                val origUrl = currentOriginalUrl ?: return
                if (Uri.parse(errUrl).host == origUrl.host) {//skip ssl errors during loading non-page resources (Chrome did like this too)
                    showCertificateErrorPage(error)
                }
            }

            override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                super.onScaleChanged(view, oldScale, newScale)
                callback.onScaleChanged(oldScale, newScale)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String, isReload: Boolean) {
                if (!isReload) {
                    callback.onVisited(url)
                }
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler?,
                host: String?,
                realm: String?
            ) {
                val userNameEdit = EditText(context).also {
                    it.hint = context.getString(com.phlox.tvwebbrowser.common.R.string.username)
                    it.isSingleLine = true
                }
                val passwordEdit = EditText(context).also {
                    it.hint = context.getString(com.phlox.tvwebbrowser.common.R.string.password)
                    it.isSingleLine = true
                    it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                val container = LinearLayout(context).also {
                    it.orientation = LinearLayout.VERTICAL
                    it.addView(userNameEdit)
                    it.addView(passwordEdit)
                }
                AlertDialog.Builder(context)
                    .setTitle(R.string.http_auth_title)
                    .setCancelable(false)
                    .setView(container)
                    .setPositiveButton(android.R.string.ok) { _: DialogInterface, _:Int ->
                        handler?.proceed(userNameEdit.text.toString(), passwordEdit.text.toString())
                    }
                    .setNegativeButton(android.R.string.cancel) { _: DialogInterface, _:Int ->
                        handler?.cancel()
                    }
                    .show()
            }
        }

        webChromeClient = webChromeClient_

        setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            Log.i(TAG, "DownloadListener.onDownloadStart url: $url")
            if (url.startsWith("blob:")) {
                //nop. we handle this by injected js on onPageFinished
            } else {
                callback.onDownloadStart(url, userAgent, contentDisposition, mimetype, contentLength)
            }
        }

        addJavascriptInterface(jsInterface, "TVBro")
        addJavascriptInterface(WebViewAdBlock.JSInterface(), "TVBroAdblock")
        if (documentStartScriptSupported) {
            WebViewCompat.addDocumentStartJavaScript(this, WebViewAdBlock.contentScript(context), setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(this,
                context.assets.open("autofullscreen.js").bufferedReader().use { it.readText() }, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(this, MediaTools.webViewScript(context), setOf("*"))
        }
    }

    /** page-level filter exceptions are the same for all requests of a page, cache them */
    private fun adBlockPageFlags(pageUrl: String?): Int {
        if (pageUrl == null) return 0
        adBlockPageFlagsCache?.let { if (it.first == pageUrl) return it.second }
        val flags = WebViewAdBlock.engine?.network?.pageFlags(pageUrl) ?: return 0
        adBlockPageFlagsCache = pageUrl to flags
        return flags
    }

    override fun restoreState(inState: Bundle): WebBackForwardList? {
        val result = super.restoreState(inState)
        currentOriginalUrl = url?.toUri()
        return result
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (virtualCursorMode && DPADNavigationEventsAdapter.isNavigationGenericMotionSource(event.source))
            return false
        return super.dispatchGenericMotionEvent(event)
    }

    private fun showCertificateErrorPage(error: SslError) {
        callback.onPageCertificateError(error.url)
        lastSSLError = error
        val url = INTERNAL_SCHEME + INTERNAL_SCHEME_WARNING_DOMAIN +
                "?type=" + INTERNAL_SCHEME_WARNING_DOMAIN_TYPE_CERT +
                "&url=" + URLEncoder.encode(error.url, "UTF-8")
        loadUrl(url)
    }

    override fun loadUrl(url: String) {
        when {
            Config.HOME_URL_ALIAS == url -> {
                when (config.homePageMode) {
                    Config.HomePageMode.BLANK -> {
                        loadDataWithBaseURL(null, "", "text/html", "UTF-8", null)
                    }
                    Config.HomePageMode.CUSTOM, Config.HomePageMode.SEARCH_ENGINE -> {
                        try {
                            currentOriginalUrl = config.homePage.toUri()
                            super.loadUrl(config.homePage)
                        } catch (e: Exception) {
                            Log.e(TAG, "LoadUrl error", e)
                            loadDataWithBaseURL(null, "", "text/html", "UTF-8", null)
                        }

                    }
                    Config.HomePageMode.HOME_PAGE -> {
                        currentOriginalUrl = Config.HOME_PAGE_URL.toUri()
                        super.loadUrl(Config.HOME_PAGE_URL)
                    }
                }

            }
            url.startsWith(INTERNAL_SCHEME) -> {
                val uri = Uri.parse(url)
                when (uri.authority) {
                    INTERNAL_SCHEME_WARNING_DOMAIN -> {
                        when (uri.getQueryParameter("type")) {
                            INTERNAL_SCHEME_WARNING_DOMAIN_TYPE_CERT -> {
                                val data = context.assets.open("pages/warning-certificate.html").bufferedReader().use { it.readText() }
                                loadDataWithBaseURL("file:///android_asset/", data, "text/html", "UTF-8", uri.getQueryParameter("url"))
                            }
                        }
                    }
                }
            }
            else -> {
                currentOriginalUrl = Uri.parse(url)
                super.loadUrl(url)
            }
        }
    }

    private fun getGenericJSInjects(): String {
        var injects = genericInjects
        if (injects == null) {
            injects =
                context.assets.open("generic_injects.js").bufferedReader().use { it.readText() }
            genericInjects = injects
        }
        return injects
    }

    fun renderThumbnail(bitmap: Bitmap?): Bitmap? {
        if (width == 0 || height == 0) return null
        var thumbnail = bitmap
        if (thumbnail == null) {
            try {
                thumbnail = createBitmap(width, height, Bitmap.Config.RGB_565)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        if (thumbnail == null) {
            return null
        }
        val canvas = Canvas(thumbnail)
        val scaleFactor = thumbnail.width / width.toFloat()
        canvas.scale(scaleFactor, scaleFactor)
        canvas.translate(-scrollX.toFloat() * scaleFactor, -scrollY.toFloat() * scaleFactor)
        super.draw(canvas)
        return thumbnail
    }

    /** A media key of the remote for the media of the page */
    fun mediaCommand(command: MediaCommand) = mediaControls.command(command)

    fun seekMediaTo(position: Double) = mediaControls.seekTo(position)

    fun seekMediaBy(delta: Double) = mediaControls.seekBy(delta)

    private class PendingErrorPage(val url: String, val page: String)

    /** Shows the error page of [kind] for [url] as a page of its own (a new view, which has no page to replace) */
    fun showLoadErrorPage(kind: LoadErrorKind, url: String, errorCode: String) {
        loadDataWithBaseURL(null, LoadErrorPage.create(context, kind, url, errorCode), "text/html", "UTF-8", url)
    }

    /**
     * Shows the error page in place of the one of WebView, which is in the history already: writing it over
     * that one adds no entry. If its scripts do not run there, it is loaded instead (Back then goes through
     * the page of WebView too)
     */
    private fun showErrorPage(errorPage: PendingErrorPage) {
        evaluateJavascript(WebViewLoadErrors.replaceDocumentScript(errorPage.page)) { result ->
            if (result != "true") {
                Log.w(TAG, "Failed to replace the error page of WebView: $result")
                loadDataWithBaseURL(null, errorPage.page, "text/html", "UTF-8", errorPage.url)
            }
        }
    }

    fun hideCustomView() {
        webChromeClient_.onHideCustomView()
    }

    fun onFilePicked(data: Intent) {
        pickFileCallback?.apply {
            if (data.data != null) {
                val uris = arrayOf(data.data!!)
                onReceiveValue(uris)
            }
        }
    }

    fun onPermissionsResult(permissions: Array<String>, grantResults: IntArray, typeGeo: Boolean) {
        if (typeGeo) geoPermissionsCallback?.apply {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                this.invoke(geoPermissionOrigin, true, true)
            } else {
                this.invoke(geoPermissionOrigin, false, false)
            }
            geoPermissionsCallback = null
            geoPermissionOrigin = null


        } else {
            pagePermissions.onAndroidPermissionsResult(permissions, grantResults)
        }
    }

    fun onUpdateAdblockSetting(adblockEnabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = adblockEnabled
        }
    }

    fun setVirtualCursorMode(enabled: Boolean) {
        this.virtualCursorMode = enabled
    }
}

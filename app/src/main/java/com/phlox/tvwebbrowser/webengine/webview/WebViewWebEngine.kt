package com.phlox.tvwebbrowser.webengine.webview

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.adblock.WebViewAdBlock
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.LoadErrorKind
import com.phlox.tvwebbrowser.webengine.MediaPlaybackSupport
import com.phlox.tvwebbrowser.webengine.MediaState
import com.phlox.tvwebbrowser.webengine.MediaCommand
import com.phlox.tvwebbrowser.webengine.PageVideo
import com.phlox.tvwebbrowser.webengine.PageVideosSupport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.phlox.tvwebbrowser.webengine.ContentFilter
import com.phlox.tvwebbrowser.webengine.MediaTools
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.WebEngineProvider
import com.phlox.tvwebbrowser.webengine.WebEngineProviderCallback
import com.phlox.tvwebbrowser.webengine.WebEngineWindowProviderCallback
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import com.phlox.tvwebbrowser.widgets.cursor.CursorLayout


class WebViewWebEngine(val tab: WebTabState) : WebEngine, MediaPlaybackSupport, PageVideosSupport,
    CursorDrawerDelegate.Callback {
    private var webView: WebViewEx? = null
    internal var callback: WebEngineWindowProviderCallback? = null
    private var viewParent: CursorLayout? = null
    private var fullScreenView: View? = null
    private val _mediaState = MutableStateFlow<MediaState?>(null)
    override val mediaState: StateFlow<MediaState?> = _mediaState.asStateFlow()
    private val _pageVideos = MutableStateFlow<List<PageVideo>>(emptyList())
    override val pageVideos: StateFlow<List<PageVideo>> = _pageVideos.asStateFlow()
    private val _mediaVideo = MutableStateFlow<PageVideo?>(null)
    override val mediaVideo: StateFlow<PageVideo?> = _mediaVideo.asStateFlow()
    //the page whose process was gone, for the crash page of the next view
    private var lostPage: LostPage? = null

    private class LostPage(val url: String, val crashed: Boolean)
    private val permissionsRequests = HashMap<Int, Boolean>()//request code, isGeolocationPermissionRequest
    private val jsInterface = AndroidJSInterface(this)

    override fun getWebEngineName(): String = "WebView"

    override fun isSameSession(internalRepresentation: Any): Boolean {
        return internalRepresentation == webView
    }

    override val url: String?
        get() = webView?.url

    override var userAgentString: String? = null
        set(value) {
            field = value
            if (value != null) {
                webView?.settings?.userAgentString = value
            }
        }

    override fun saveState(): Any {
        val bundle = Bundle()
        webView?.saveState(bundle)
        return bundle
    }

    override fun restoreState(savedInstanceState: Any) {
        if (savedInstanceState is Bundle) {
            webView?.restoreState(savedInstanceState)
        } else {
            throw IllegalArgumentException("savedInstanceState must be Bundle")
        }
    }

    override fun stateFromBytes(bytes: ByteArray): Any? =
        Utils.bytesToBundle(bytes)

    override fun loadUrl(url: String) {
        val allowed = ContentFilter.check(url)
        if (allowed == null) {
            callback?.onMediaToolsEvent(MediaTools.blockedEvent(url))
            return
        }
        loadAllowedUrl(allowed)
    }

    private fun loadAllowedUrl(url: String) {
        webView?.loadUrl(url)
    }

    override fun canGoForward(): Boolean {
        return webView?.canGoForward() ?: false
    }

    override fun goForward() {
        webView?.goForward()
    }

    override fun canZoomIn(): Boolean {
        return webView?.canZoomIn() ?: false
    }

    override fun zoomIn() {
        webView?.zoomIn()
    }

    override fun canZoomOut(): Boolean {
        return webView?.canZoomOut() ?: false
    }

    override fun zoomOut() {
        webView?.zoomOut()
    }

    override fun zoomBy(zoomBy: Float) {
        webView?.zoomBy(zoomBy)
    }

    override fun evaluateJavascript(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    override fun setNetworkAvailable(connected: Boolean) {
        webView?.setNetworkAvailable(connected)
    }

    override fun getView(): View? {
        return webView
    }

    @Throws(Exception::class)
    override fun getOrCreateView(activityContext: Context): View {
        if (webView == null) {
            webView = WebViewEx(activityContext, webViewCallback, jsInterface)
        }
        return webView!!
    }

    override fun canGoBack(): Boolean {
        return webView?.canGoBack() ?: false
    }

    override fun goBack() {
        webView?.goBack()
    }

    override fun reload() {
        webView?.reload()
    }

    override fun onFilePicked(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            return
        }
        webView?.onFilePicked(data)
    }

    override fun onResume() {
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
    }

    override fun onUpdateAdblockSetting(newState: Boolean) {
        webView?.onUpdateAdblockSetting(newState)
    }

    override fun hideFullscreenView() {
        webView?.hideCustomView()
    }

    override fun togglePlayback() {
        webView?.mediaCommand(MediaCommand.TOGGLE)
    }

    override fun stopPlayback() {
        webView?.mediaCommand(MediaCommand.STOP)
    }

    override fun rewind() {
        webView?.mediaCommand(MediaCommand.REWIND)
    }

    override fun fastForward() {
        webView?.mediaCommand(MediaCommand.FAST_FORWARD)
    }

    override fun seekTo(position: Double) {
        webView?.seekMediaTo(position)
    }

    override fun seekBy(delta: Double) {
        webView?.seekMediaBy(delta)
    }

    override fun pausePlayback() {
        webView?.mediaCommand(MediaCommand.PAUSE)
    }

    override suspend fun renderThumbnail(bitmap: Bitmap?): Bitmap? {
        return webView?.renderThumbnail(bitmap)
    }

    override fun onAttachToWindow(callback: WebEngineWindowProviderCallback, parent: ViewGroup) {
        this.callback = callback
        if (webView == null) {
            throw IllegalStateException("WebView is null")
        }
        this.viewParent = parent as CursorLayout
        parent.removeAllViews()
        parent.addView(webView)
        viewParent?.cursorDrawerDelegate?.callback = this
        onResume()
    }

    override fun onDetachFromWindow(completely: Boolean, destroyTab: Boolean) {
        onPause()
        (webView?.parent as? ViewGroup)?.removeView(webView)
        callback = null
        if (completely) {
            webView = null
            _mediaState.value = null
            _pageVideos.value = emptyList()
            _mediaVideo.value = null
        }
    }

    override fun trimMemory() {
        val webView = webView
        if (webView != null && !webView.isAttachedToWindow) {
            this.webView = null
            _mediaState.value = null
            _pageVideos.value = emptyList()
            _mediaVideo.value = null
        }
    }

    override fun onPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray): Boolean {
        val isGeolocationPermissionRequest = permissionsRequests[requestCode] ?: return false
        permissionsRequests.remove(requestCode)
        if (grantResults.isEmpty()) return true
        webView?.onPermissionsResult(permissions, grantResults, isGeolocationPermissionRequest)
        return true
    }

    override fun onLongPress(x: Int, y: Int) {
        webView?.let {
            it.evaluateJavascript(Scripts.LONG_PRESS_SCRIPT) { href ->
                val linkUrl = if (href == "null") null else href
                webViewCallback.onContextMenu(
                    it.currentOriginalUrl.toString(),
                    linkUrl, x, y
                )
            }
        }
    }

    override fun isVirtualCursorMode(): Boolean {
        return viewParent?.cursorEnabled ?: true
    }

    override fun setVirtualCursorMode(enabled: Boolean) {
        viewParent?.cursorEnabled = enabled
        if (enabled) {
            viewParent?.cursorDrawerDelegate?.animateAppearing()
        }
        webView?.setVirtualCursorMode(enabled)
    }

    override fun getCursorDrawerDelegate(): CursorDrawerDelegate? {
        return viewParent?.cursorDrawerDelegate
    }

    /** After the page process was gone: the new view shows the crash page with a Reload button */
    fun showLostPage() {
        val page = lostPage ?: return
        lostPage = null
        webView?.showLoadErrorPage(LoadErrorKind.CRASHED, page.url,
            if (page.crashed) "RENDER_PROCESS_CRASHED" else "RENDER_PROCESS_KILLED")
    }

    private val webViewCallback = object : WebViewEx.Callback {
        override fun onMediaStateChanged(state: MediaState?) {
            _mediaState.value = state
        }

        override fun onPageVideosChanged(videos: List<PageVideo>) {
            _pageVideos.value = videos
        }

        override fun onMediaVideoChanged(video: PageVideo?) {
            _mediaVideo.value = video
        }

        override fun onRenderProcessGone(didCrash: Boolean) {
            val view = webView ?: return
            val url = view.url ?: tab.url
            if (fullScreenView != null) {
                fullScreenView = null
                callback?.onExitFullscreen()
            }
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            webView = null
            _mediaState.value = null
            _pageVideos.value = emptyList()
            _mediaVideo.value = null
            lostPage = LostPage(url, didCrash)
            //the current tab gets a new view now, a background one when it is shown
            callback?.onEngineViewLost()
        }

        override fun getActivity(): Activity? {
            return callback?.getActivity()
        }

        override fun onOpenInNewTabRequested(url: String) {
            callback?.onOpenInNewTabRequested(url, true)
        }

        override fun onDownloadRequested(url: String) {
            callback?.onDownloadRequested(url)
        }

        override fun onThumbnailError() {
            //nop for now
        }

        override fun onShowCustomView(view: View) {
            callback?.onPrepareForFullscreen()
            webView?.visibility = View.GONE
            viewParent?.apply {
                addView(view)
            }
            fullScreenView = view
        }

        override fun onHideCustomView() {
            if (fullScreenView != null) {
                viewParent?.removeView(fullScreenView)
                fullScreenView = null
            }
            webView?.visibility = View.VISIBLE
            callback?.onExitFullscreen()
        }

        override fun onProgressChanged(newProgress: Int) {
            callback?.onProgressChanged(newProgress)
        }

        override fun onReceivedTitle(title: String) {
            callback?.onReceivedTitle(title)
        }

        override fun requestPermissions(array: Array<String>, geo: Boolean) {
            val requestCode = callback?.requestPermissions(array) ?: return
            permissionsRequests[requestCode] = geo
        }

        override fun onShowFileChooser(intent: Intent): Boolean {
            return callback?.onShowFileChooser(intent) ?: false
        }

        override fun onReceivedIcon(icon: Bitmap) {
            callback?.onReceivedIcon(icon)
        }

        override fun shouldOverrideUrlLoading(url: String): Boolean {
            return callback?.shouldOverrideUrlLoading(url) ?: false
        }

        override fun onPageStarted(url: String?) {
            callback?.onPageStarted(url)
        }

        override fun onPageFinished(url: String?) {
            callback?.onPageFinished(url)
        }

        override fun onPageCertificateError(url: String?) {
            callback?.onPageCertificateError(url)
        }

        override fun isAdBlockingEnabled(): Boolean {
            return callback?.isAdBlockingEnabled() ?: false
        }

        override fun onBlockedAd(url: Uri) {
            callback?.onBlockedAd(url.toString())
        }

        override fun isDialogsBlockingEnabled(): Boolean {
            return callback?.isDialogsBlockingEnabled() ?: false
        }

        override fun onBlockedDialog(newTab: Boolean) {
            callback?.onBlockedDialog(newTab)
        }

        override fun onCreateWindow(dialog: Boolean, userGesture: Boolean): WebViewEx? {
            return callback?.onCreateWindow(dialog, userGesture) as? WebViewEx
        }

        override fun closeWindow(window: WebView) {
            callback?.closeWindow(window)
        }

        override fun onDownloadStart(url: String, userAgent: String, contentDisposition: String, mimetype: String?, contentLength: Long) {
            callback?.onDownloadRequested(url, userAgent, contentDisposition, mimetype, contentLength)
        }

        override fun onScaleChanged(oldScale: Float, newScale: Float) {
            callback?.onScaleChanged(oldScale, newScale)
        }

        override fun onCopyTextToClipboardRequested(url: String) {
            callback?.onCopyTextToClipboardRequested(url)
        }

        override fun onShareUrlRequested(url: String) {
            callback?.onShareUrlRequested(url)
        }

        override fun onOpenInExternalAppRequested(url: String) {
            callback?.onOpenInExternalAppRequested(url)
        }

        override fun onVisited(url: String) {
            callback?.onVisited(url)
        }

        override fun onContextMenu(baseUrl:String?, href: String?, x: Int, y: Int) {
            callback?.onContextMenu(
                viewParent!!.cursorDrawerDelegate,
                baseUri = baseUrl,
                linkUri = href,
                srcUri = null,
                title = null,
                altText = null,
                textContent = null,
                x = x,
                y = y
            )
        }
    }

    companion object {
        init {
            WebEngineFactory.registerProvider(WebEngineProvider("WebView", object : WebEngineProviderCallback {
                override suspend fun initialize(context: Context, webViewContainer: CursorLayout) {
                    WebViewAdBlock.start(context)
                }

                override fun createWebEngine(tab: WebTabState): WebEngine {
                    return WebViewWebEngine(tab)
                }

                override suspend fun clearCache(ctx: Context) {
                    WebView(ctx).clearCache(true)
                }

                override fun onThemeSettingUpdated(value: Config.Theme) {
                    //nop
                }

                override fun onAdBlockSettingUpdated(enabled: Boolean) {
                    //nop, WebViewAdBlock checks the setting on every request
                }

                override fun getWebEngineVersionString(): String {
                    val webViewPackage = WebViewCompat.getCurrentWebViewPackage(AppContext.get())
                    return (webViewPackage?.packageName ?: "unknown") + ":" + (webViewPackage?.versionName ?: "unknown")
                }
            }))
        }
    }
}
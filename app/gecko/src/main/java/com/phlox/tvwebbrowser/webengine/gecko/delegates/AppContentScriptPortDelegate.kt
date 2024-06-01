package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.util.Log
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

class AppContentScriptPortDelegate(val port: WebExtension.Port, val webEngine: GeckoWebEngine): WebExtension.PortDelegate {
    override fun onPortMessage(message: Any, port: WebExtension.Port) {

    }

    override fun onDisconnect(port: WebExtension.Port) {
        Log.d(TAG, "onDisconnect")
        webEngine.appContentScriptPortDelegate = null
    }

    fun updateSelection(x: Int, y: Int, width: Int, height: Int) {
        val msg = JSONObject()
        msg.put("action", "updateSelection")
        msg.put("data", JSONObject().put("x", x).put("y", y)
            .put("width", width).put("height", height))
        port.postMessage(msg)
    }

    fun clearSelection() {
        val msg = JSONObject()
        msg.put("action", "clearSelection")
        port.postMessage(msg)
    }

    companion object {
        val TAG: String = AppContentScriptPortDelegate::class.java.simpleName
    }
}
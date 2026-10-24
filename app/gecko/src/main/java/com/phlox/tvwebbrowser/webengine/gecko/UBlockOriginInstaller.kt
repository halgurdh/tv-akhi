package com.phlox.tvwebbrowser.webengine.gecko

import android.util.Log
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Installs uBlock Origin from addons.mozilla.org (signed XPI) and keeps it updated.
 * Install/update prompts are auto-accepted only for uBlock Origin, everything else is denied.
 */
object UBlockOriginInstaller {
    private val TAG: String = UBlockOriginInstaller::class.java.simpleName
    const val UBLOCK_ORIGIN_ID = "uBlock0@raymondhill.net"
    private const val UBLOCK_ORIGIN_XPI_URL =
        "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"

    fun init(runtime: GeckoRuntime) {
        val controller = runtime.webExtensionController
        controller.promptDelegate = object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>
            ): GeckoResult<WebExtension.PermissionPromptResponse> {
                val allowed = extension.id == UBLOCK_ORIGIN_ID
                return GeckoResult.fromValue(
                    WebExtension.PermissionPromptResponse(allowed, allowed, false))
            }

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<String>,
                newOrigins: Array<String>,
                newDataCollectionPermissions: Array<String>
            ): GeckoResult<AllowOrDeny> {
                return GeckoResult.fromValue(
                    if (extension.id == UBLOCK_ORIGIN_ID) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }
        }

        controller.list().accept({ extensions ->
            val installed = extensions?.firstOrNull { it.id == UBLOCK_ORIGIN_ID }
            if (installed == null) {
                Log.d(TAG, "installing uBlock Origin")
                controller.install(UBLOCK_ORIGIN_XPI_URL).accept(
                    { Log.d(TAG, "uBlock Origin installed: ${it?.metaData?.version}") },
                    { e -> Log.e(TAG, "Error installing uBlock Origin", e) })
            } else {
                controller.update(installed).accept(
                    { Log.d(TAG, "uBlock Origin update check done: ${it?.metaData?.version}") },
                    { e -> Log.e(TAG, "Error updating uBlock Origin", e) })
            }
        }, { e -> Log.e(TAG, "Error listing extensions", e) })
    }
}

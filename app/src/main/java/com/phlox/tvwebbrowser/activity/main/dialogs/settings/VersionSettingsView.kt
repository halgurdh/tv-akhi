package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.text.Html
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ScrollView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.IncognitoModeMainActivity
import com.phlox.tvwebbrowser.activity.main.AutoUpdateModel
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.activity.main.SettingsModel
import com.phlox.tvwebbrowser.databinding.ViewSettingsVersionBinding
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.utils.activity
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import androidx.core.net.toUri

@SuppressLint("SetTextI18n")
class VersionSettingsView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    companion object {
        private const val URL_SUPPORT_AUTHOR = "https://github.com/halgurdh"
        private const val URL_LICENSE =
            "https://raw.githubusercontent.com/truefedex/tv-bro/refs/heads/master/LICENSE.md"
        private const val URL_PRIVACY_POLICY =
            "https://raw.githubusercontent.com/truefedex/tv-bro/refs/heads/master/PRIVACY.md"
    }
    private var vb = ViewSettingsVersionBinding.inflate(LayoutInflater.from(getContext()), this, true)
    var config = AppContext.provideConfig()
    var settingsModel = ActiveModelsRepository.get(SettingsModel::class, activity!!)
    var autoUpdateModel = ActiveModelsRepository.get(AutoUpdateModel::class, activity!!)
    var callback: Callback? = null

    interface Callback {
        fun onNeedToCloseSettings()
    }

    init {
        vb.tvVersion.text = context.getString(R.string.version_s, BuildConfig.VERSION_NAME)

        vb.tvBuildFlavor.text = context.getString(
            R.string.build_flavor_s,
            BuildConfig.FLAVOR_appstore,
            BuildConfig.FLAVOR_webengine
        )

        val engineVersion = "Engine: " + WebEngineFactory.getWebEngineVersionString()
        vb.tvWebViewVersion.text = engineVersion

        vb.tvLink.text = Html.fromHtml("<p><u>https://github.com/truefedex/tv-bro</u></p>",
            Html.FROM_HTML_MODE_LEGACY)
        vb.tvLink.setOnClickListener {
            loadUrl(vb.tvLink.text.toString())
        }

        vb.tvSupportAuthor.text = context.getString(R.string.support_the_author)
        vb.tvSupportAuthor.setOnClickListener {
            loadUrl(URL_SUPPORT_AUTHOR)
        }

        vb.tvLicense.text = context.getString(R.string.view_license)
        vb.tvLicense.setOnClickListener {
            loadUrl(URL_LICENSE)
        }

        vb.tvPrivacy.text = context.getString(R.string.privacy_policy)
        vb.tvPrivacy.setOnClickListener {
            loadUrl(URL_PRIVACY_POLICY)
        }

        vb.tvUkraine.setOnClickListener {
            loadUrl("https://tv-bro-3546c.web.app/msg001.html")
        }

        if (BuildConfig.BUILT_IN_AUTO_UPDATE) {
            vb.chkAutoCheckUpdates.isChecked = autoUpdateModel.needAutoCheckUpdates

            vb.chkAutoCheckUpdates.setOnCheckedChangeListener { _, isChecked ->
                autoUpdateModel.saveAutoCheckUpdates(isChecked)

                updateUIVisibility()
            }

            vb.btnCheckUpdates.setOnClickListener { checkNow() }

            vb.btnUpdate.setOnClickListener {
                callback?.onNeedToCloseSettings()
                autoUpdateModel.showUpdateDialogIfNeeded(activity as MainActivity, true)
            }

            updateUIVisibility()
        } else {
            vb.chkAutoCheckUpdates.visibility = View.INVISIBLE
            vb.btnCheckUpdates.visibility = View.GONE
        }
    }

    /** The button: asks GitHub now, then the update dialog or a word that there's nothing new */
    private fun checkNow() {
        vb.btnCheckUpdates.isEnabled = false
        vb.btnCheckUpdates.setText(R.string.checking_for_updates)
        autoUpdateModel.checkUpdate(true) { isChecked ->
            vb.btnCheckUpdates.isEnabled = true
            vb.btnCheckUpdates.setText(R.string.check_for_updates)
            when {
                !isChecked -> Toast.makeText(context, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                autoUpdateModel.updateChecker.hasUpdate() -> {
                    callback?.onNeedToCloseSettings()
                    autoUpdateModel.showUpdateDialogIfNeeded(activity as MainActivity, true)
                }
                else -> Toast.makeText(context, context.getString(R.string.up_to_date_s, BuildConfig.VERSION_NAME),
                    Toast.LENGTH_LONG).show()
            }
            updateUIVisibility()
        }
    }

    private fun loadUrl(url: String) {
        callback?.onNeedToCloseSettings()
        val activityClass = if (settingsModel.config.incognitoMode)
            IncognitoModeMainActivity::class.java else MainActivity::class.java
        val intent = Intent(activity, activityClass)
        intent.data = url.toUri()
        activity?.startActivity(intent)
    }

    private fun updateUIVisibility() {
        //only with auto check on: off, the TV asks GitHub only when the button is pressed
        if (autoUpdateModel.updateChecker.versionCheckResult == null && autoUpdateModel.needAutoCheckUpdates) {
            autoUpdateModel.checkUpdate(false) {
                if (autoUpdateModel.updateChecker.versionCheckResult != null) {
                    updateUIVisibility()
                }
            }
            return
        }

        val hasUpdate = autoUpdateModel.updateChecker.hasUpdate()
        vb.tvNewVersion.visibility = if (hasUpdate) View.VISIBLE else View.INVISIBLE
        vb.btnUpdate.visibility = if (hasUpdate) View.VISIBLE else View.INVISIBLE
        if (hasUpdate) {
            vb.tvNewVersion.text = context.getString(R.string.new_version_available_s, autoUpdateModel.updateChecker.versionCheckResult!!.latestVersionName)
        }
    }
}
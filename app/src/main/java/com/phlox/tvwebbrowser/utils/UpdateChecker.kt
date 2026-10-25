package com.phlox.tvwebbrowser.utils

import android.app.Activity
import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.Html
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.phlox.tvwebbrowser.R
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from the GitHub releases of the app (like SmartTube): the latest release's version name against ours, its
 * notes as the changelog, and the APK for this TV's CPU among its files (tv-akhi-armeabi-v7a.apk, ...).
 */
class UpdateChecker(val currentVersionName: String) {
    var versionCheckResult: UpdateCheckResult? = null

    class UpdateCheckResult(val latestVersionName: String, val url: String, val changes: String)
    interface DialogCallback {
        fun download()
        fun later()
        fun settings()
    }

    /** [repo]: "owner/name" on GitHub */
    fun check(repo: String) {
        val urlConnection = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        urlConnection.setRequestProperty("Accept", "application/vnd.github+json")
        urlConnection.connectTimeout = TIMEOUT_MS
        urlConnection.readTimeout = TIMEOUT_MS
        try {
            val content = urlConnection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(content)
            val assets = json.getJSONArray("assets")
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.getString("name").endsWith(".apk") }
            //the CPU's ABIs, best first (a 64-bit TV can also run the 32-bit APK)
            val apk = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
                apks.firstOrNull { it.getString("name").endsWith("$abi.apk") }
            } ?: throw java.io.IOException("No APK for this TV in the latest release")
            versionCheckResult = UpdateCheckResult(json.getString("tag_name").removePrefix("v"),
                apk.getString("browser_download_url"), json.optString("body"))
        } finally {
            urlConnection.disconnect()
        }
    }

    fun showUpdateDialog(context: Activity, callback: DialogCallback) {
        val version = versionCheckResult ?: return
        if (!hasUpdate()) return
        val textView = TextView(context)
        textView.text = Html.fromHtml("<b>${version.latestVersionName}</b><br>" + notesToHtml(version.changes),
            Html.FROM_HTML_MODE_LEGACY)
        val padding = Utils.D2P(context, 25f).toInt()
        textView.setPadding(padding, padding, padding, padding)
        //release notes can be long
        val scroll = android.widget.ScrollView(context)
        scroll.addView(textView)
        AlertDialog.Builder(context)
                .setTitle(R.string.new_version_dialog_title)
                .setView(scroll)
                .setPositiveButton(R.string.download) { _, _ -> callback.download() }
                .setNegativeButton(R.string.later) { _, _ -> callback.later() }
                .setNeutralButton(R.string.settings) { _, _ -> callback.settings() }
                .show()
    }

    suspend fun downloadUpdate(context: Activity, modelScope: CoroutineScope) {
        val update = versionCheckResult ?: return
        val dialog = ProgressDialog(context)
        dialog.setCancelable(true)
        dialog.setMessage(context.getString(R.string.downloading_file))
        dialog.isIndeterminate = false
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        dialog.show()
        var downloaded = false
        var downloadedFile = Utils.createTempFile(context, UPDATE_APK_FILE_NAME)
        var nextDialogUpdateTime = System.currentTimeMillis()

        val job = modelScope.launch(Dispatchers.IO) io_launch@{
            var input: InputStream? = null
            var output: OutputStream? = null
            var connection: HttpURLConnection? = null
            try {
                val url = URL(update.url)
                connection = url.openConnection() as HttpURLConnection
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, (connection.responseMessage ?: context.getString(R.string.error)) +
                                " (${connection.responseCode})", Toast.LENGTH_LONG).show()
                    }
                    return@io_launch
                }

                var fileLength = connection.contentLength.toLong()
                if (fileLength == -1L) {
                    withContext(Dispatchers.Main) {
                        dialog.isIndeterminate = true
                        dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER)
                    }
                }

                input = connection.inputStream
                output = downloadedFile.outputStream()
                val data = ByteArray(8 * 1024)
                var total: Long = 0
                var count: Int
                do {
                    if (!isActive) {
                        return@io_launch
                    }
                    count = input!!.read(data)
                    if (count > 0) {
                        total += count.toLong()
                        output.write(data, 0, count)
                    }
                    if (fileLength != -1L && System.currentTimeMillis() >= nextDialogUpdateTime) {
                        withContext(Dispatchers.Main) {
                            val progress = total * 100 / fileLength
                            dialog.progress = progress.toInt()
                        }
                        nextDialogUpdateTime += 50
                    }
                } while (count != -1)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, e.toString(), Toast.LENGTH_LONG).show()
                }
                return@io_launch
            } finally {
                output?.close()
                input?.close()
                connection?.disconnect()
            }

            downloaded = true
        }
        dialog.setOnCancelListener {
            job.cancel()
        }
        job.join()
        dialog.dismiss()

        if (!downloaded) return

        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(downloadedFile.extension)
        val apkURI = FileProvider.getUriForFile(
                context,
                context.applicationContext.packageName + ".provider", downloadedFile)

        val install = Intent(Intent.ACTION_INSTALL_PACKAGE)
        install.setDataAndType(apkURI, mimeType)
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(install)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.error, Toast.LENGTH_SHORT).show()
        }
    }

    fun hasUpdate(): Boolean {
        val versionCheckResult = this.versionCheckResult ?: return false
        return compareVersions(versionCheckResult.latestVersionName, currentVersionName) > 0
    }

    companion object {
        private const val UPDATE_APK_FILE_NAME = "update.apk"
        private const val TIMEOUT_MS = 15000

        /** "1.1.0" against "1.0.10": by number, part by part (a missing part is 0) */
        fun compareVersions(a: String, b: String): Int {
            val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val diff = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (diff != 0) return diff
            }
            return 0
        }

        /** The release notes are Markdown: bold, list items and lines, the rest as text */
        fun notesToHtml(markdown: String): String = android.text.TextUtils.htmlEncode(markdown)
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("(?m)^\\s*[-*] "), "• ")
            .replace(Regex("(?m)^#+\\s*(.*)$"), "<b>$1</b>")
            .replace("\r", "")
            .replace("\n", "<br>")
        fun clearTempFilesIfAny(context: Context) {
            var tempFile = File(context.cacheDir, UPDATE_APK_FILE_NAME)
            if (tempFile.exists()) {
                tempFile.delete()
            }
            tempFile = File(context.externalCacheDir, UPDATE_APK_FILE_NAME)
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}
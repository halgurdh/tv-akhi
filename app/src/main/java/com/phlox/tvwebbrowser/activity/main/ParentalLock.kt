package com.phlox.tvwebbrowser.activity.main

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.utils.Utils
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The parental PIN: with it set, turning off the adult block, the haram filter or No music, going incognito and
 * deleting the history need the PIN.
 * Only a salted hash of the PIN is kept.
 */
object ParentalLock {
    private const val MIN_LENGTH = 4

    private val config get() = AppContext.provideConfig()

    val isSet: Boolean
        get() = config.parentalPin != null

    /** Runs the action right away without a PIN, after the right PIN with one */
    fun unlock(context: Context, action: () -> Unit) {
        if (!isSet) {
            action()
            return
        }
        askPin(context, R.string.parental_pin_enter) { pin ->
            if (matches(pin)) action() else Utils.showToast(context, R.string.parental_pin_wrong)
        }
    }

    /** A new PIN, entered twice */
    fun setUp(context: Context, onDone: () -> Unit) {
        askPin(context, R.string.parental_pin_new) { pin ->
            if (pin.length < MIN_LENGTH) {
                Utils.showToast(context, R.string.parental_pin_too_short)
                return@askPin
            }
            askPin(context, R.string.parental_pin_repeat) { repeated ->
                if (repeated == pin) {
                    set(pin)
                    Utils.showToast(context, R.string.parental_pin_set)
                    onDone()
                } else {
                    Utils.showToast(context, R.string.parental_pin_mismatch)
                }
            }
        }
    }

    fun remove() {
        config.parentalPin = null
    }

    private fun set(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        config.parentalPin = salt + ":" + hash(salt, pin)
    }

    private fun matches(pin: String): Boolean {
        val stored = config.parentalPin ?: return true
        val salt = stored.substringBefore(':')
        return MessageDigest.isEqual(hash(salt, pin).toByteArray(), stored.substringAfter(':').toByteArray())
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun askPin(context: Context, title: Int, onPin: (String) -> Unit) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(8))
            isSingleLine = true
        }
        val padding = (24 * context.resources.displayMetrics.density).toInt()
        val container = FrameLayout(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> onPin(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }
}

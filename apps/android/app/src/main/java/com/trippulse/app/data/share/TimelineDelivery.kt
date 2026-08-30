package com.trippulse.app.data.share

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.trippulse.app.core.Profile
import java.io.File

/**
 * Hands the finished journey's timeline to the people who followed it, over
 * WhatsApp, from the traveller's own account.
 *
 * ### What this can and cannot do, honestly
 *
 * Android gives no app the ability to send a WhatsApp message on someone's
 * behalf without them seeing it. There is no API, and there should not be —
 * an app that could silently message your contacts from your account is a
 * thing nobody should ship. What we can do, and what this does, is prepare the
 * message completely: build the PDF, address it to one recipient, and open
 * WhatsApp on that conversation with the document attached. The traveller taps
 * send. That is one tap per person, and it is genuinely *their* WhatsApp
 * sending it — which is what was asked for.
 *
 * ### What goes, and what never goes
 *
 * The **timeline only**. Journey costs are the traveller's private business
 * and are never attached, never summarised, and never mentioned in the
 * accompanying text — see [buildMessage]. The money PDF exists, but it is
 * shared by hand from the summary screen and by nothing automatic.
 *
 * Recipients are the traveller's circle: emergency contacts, who are the same
 * people a journey is shared with by default and the only followers whose
 * phone number the app holds. Someone who joined with only a journey number
 * has never given us a number to message.
 */
object TimelineDelivery {

    private const val WHATSAPP = "com.whatsapp"
    private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    /** One person the timeline can be sent to. */
    data class Recipient(val name: String, val phone: String) {
        /** WhatsApp's contact id: digits only, country code included. */
        val jid: String get() = "${phone.filter { it.isDigit() }}@s.whatsapp.net"
    }

    /** Is WhatsApp actually installed? Needs the <queries> entry in the manifest. */
    fun isAvailable(context: Context): Boolean = whatsAppPackage(context) != null

    private fun whatsAppPackage(context: Context): String? {
        val pm = context.packageManager
        return listOf(WHATSAPP, WHATSAPP_BUSINESS).firstOrNull { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /**
     * Everyone the timeline can go to: circle contacts with a usable number.
     *
     * A number shorter than eight digits is almost certainly a half-typed
     * entry rather than a real contact, and messaging it would be worse than
     * skipping it.
     */
    fun recipients(context: Context): List<Recipient> =
        Profile.contacts(context)
            .filter { it.filled }
            .map { Recipient(it.name.trim(), it.phone.trim()) }
            .filter { it.phone.count { ch -> ch.isDigit() } >= 8 }

    /**
     * The covering message. Deliberately short, and deliberately silent about
     * money.
     */
    fun buildMessage(travellerName: String?, origin: String, destination: String): String =
        buildString {
            append(if (travellerName.isNullOrBlank()) "I've" else "$travellerName has")
            append(" arrived safely. ")
            append("Here's the timeline of the journey from $origin to $destination.")
            append("\n\nSent from Koode.")
        }

    /**
     * Builds the intent that opens WhatsApp on [recipient]'s conversation with
     * the timeline attached, or null when WhatsApp isn't installed.
     *
     * The `jid` extra is how WhatsApp accepts a pre-selected recipient for a
     * document share. If a future WhatsApp ignores it the share still works —
     * it opens the contact picker instead of the conversation, which is a
     * degraded experience rather than a broken one.
     */
    fun intentFor(
        context: Context,
        recipient: Recipient,
        pdf: File,
        message: String
    ): Intent? {
        val pkg = whatsAppPackage(context) ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdf)
        return Intent(Intent.ACTION_SEND).apply {
            setPackage(pkg)
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, message)
            putExtra("jid", recipient.jid)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * A plain share sheet, for when WhatsApp isn't installed or the traveller
     * would rather choose the app themselves. The timeline still goes; only
     * the transport changes.
     */
    fun fallbackIntent(context: Context, pdf: File, message: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdf)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, message)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Send the journey timeline"
        )
    }
}

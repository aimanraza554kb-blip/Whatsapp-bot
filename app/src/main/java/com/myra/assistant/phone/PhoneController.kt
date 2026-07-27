package com.myra.assistant.phone

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import com.myra.assistant.data.repository.SettingsRepository
import com.myra.assistant.service.MyraAccessibilityService
import com.myra.assistant.util.Logger
import com.myra.assistant.util.PermissionHelper
import java.util.Locale
import org.json.JSONObject

/**
 * Executes device actions requested through MYRA. Everything here is triggered
 * either by explicit UI buttons or by [handleAssistantText], which scans the
 * spoken reply for clear action intents.
 */
class PhoneController(
    private val context: Context,
    private val settings: SettingsRepository
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun launch(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Logger.w(TAG, "No activity for intent: ${intent.action}")
        }
    }

    // ----- Apps -----
    /** Launch an installed app by name. Returns true only if an app was actually opened. */
    fun openApp(query: String): Boolean {
        val pm = context.packageManager
        val clean = query.lowercase(Locale.ROOT).trim()
        val pkg = KNOWN_APPS[clean] ?: findPackageByLabel(clean)
        if (pkg != null) {
            pm.getLaunchIntentForPackage(pkg)?.let { launch(it); return true }
        }
        Logger.w(TAG, "App not installed / not found: $query")
        return false
    }

    private fun findPackageByLabel(label: String): String? {
        val pm = context.packageManager
        val target = label.lowercase(Locale.ROOT).trim()
        val apps = pm.getInstalledApplications(PackageManager.MATCH_ALL)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
        apps.firstOrNull { pm.getApplicationLabel(it).toString().lowercase(Locale.ROOT) == target }
            ?.let { return it.packageName }
        apps.firstOrNull { pm.getApplicationLabel(it).toString().lowercase(Locale.ROOT).startsWith(target) }
            ?.let { return it.packageName }
        return apps.firstOrNull {
            pm.getApplicationLabel(it).toString().lowercase(Locale.ROOT).contains(target)
        }?.packageName
    }

    fun closeCurrentApp() {
        MyraAccessibilityService.instance?.goHome()
    }

    fun openPlayStore() = launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=apps")))
    fun openChrome(url: String) {
        val target = if (url.startsWith("http")) url else "https://www.google.com/search?q=" + Uri.encode(url)
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
    }
    fun openSettings() = launch(Intent(Settings.ACTION_SETTINGS))
    fun openCalculator() = openApp("calculator")
    fun openInstagram() = openApp("instagram")
    fun openFacebook() = openApp("facebook")

    // ----- Communication -----
    fun callContact(name: String) {
        val number = lookupNumber(name) ?: run { Logger.w(TAG, "Contact not found: $name"); return }
        if (PermissionHelper.hasPermission(context, android.Manifest.permission.CALL_PHONE)) {
            launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
        } else {
            launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        }
    }

    /** Open a WhatsApp chat and, if accessibility is on, auto-tap the send button. */
    fun whatsapp(name: String, message: String): String {
        val raw = lookupNumber(name) ?: if (name.count { it.isDigit() } >= 7) name else null
        if (raw == null) return "I couldn't find $name in your contacts"
        val number = normalizeWaNumber(raw)
        val uri = Uri.parse("https://wa.me/$number?text=" + Uri.encode(message))
        launch(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp"))
        if (message.isBlank()) return "Opened WhatsApp for $name"
        val a11y = MyraAccessibilityService.instance
        return if (a11y != null) {
            a11y.clickNodeById("com.whatsapp:id/send", "Send")
            "Sent WhatsApp message to $name"
        } else {
            "Typed the message in WhatsApp for $name, but turn on the Accessibility service so I can tap send automatically"
        }
    }

    /** Convert a locally-saved number into the international digits WhatsApp needs. */
    private fun normalizeWaNumber(raw: String): String {
        var n = raw.filter { it.isDigit() || it == '+' }.removePrefix("+")
        if (n.startsWith("00")) n = n.drop(2)
        if (n.startsWith("0")) n = DEFAULT_COUNTRY_CODE + n.drop(1)
        return n
    }

    /** Fetch current weather via wttr.in (no API key). Empty city uses IP location. */
    fun weather(city: String): String {
        return try {
            val loc = java.net.URLEncoder.encode(city.trim(), "UTF-8")
            val url = java.net.URL("https://wttr.in/$loc?format=%l:+%C,+%t+(feels+%f),+humidity+%h,+wind+%w&m")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("User-Agent", "curl/8.0")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            when {
                text.isBlank() -> "I couldn't get the weather right now"
                text.contains("Unknown location", true) -> "I couldn't find that place"
                else -> text
            }
        } catch (e: Exception) {
            "I couldn't fetch the weather right now, check the internet connection"
        }
    }

    /** Place a WhatsApp voice or video call to a saved contact. */
    fun whatsappCall(name: String, video: Boolean): String {
        if (!PermissionHelper.hasPermission(context, android.Manifest.permission.READ_CONTACTS))
            return "I need the Contacts permission to place WhatsApp calls"
        val mime = if (video) "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
            else "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
        val cursor = context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            ContactsContract.Data.DISPLAY_NAME_PRIMARY + " LIKE ? AND " + ContactsContract.Data.MIMETYPE + " = ?",
            arrayOf("%$name%", mime),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(0)
                val uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id)
                launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).setPackage("com.whatsapp"))
                return if (video) "Video calling $name on WhatsApp" else "Calling $name on WhatsApp"
            }
        }
        return "$name is not on WhatsApp or not saved in your contacts"
    }

    fun sendSms(name: String, message: String): String {
        val number = lookupNumber(name) ?: name
        if (PermissionHelper.hasPermission(context, android.Manifest.permission.SEND_SMS)) {
            return try {
                val sms = context.getSystemService(SmsManager::class.java)
                val parts = sms.divideMessage(message)
                sms.sendMultipartTextMessage(number, null, parts, null, null)
                "SMS sent to $name"
            } catch (e: Exception) {
                Logger.e(TAG, "Direct SMS failed", e)
                launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", message))
                "Opened SMS composer for $name"
            }
        }
        launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", message))
        return "Opened SMS composer for $name (grant SMS permission to send directly)"
    }

    fun email(to: String, subject: String, body: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
        launch(intent)
    }

    fun lookupNumber(name: String): String? {
        if (!PermissionHelper.hasPermission(context, android.Manifest.permission.READ_CONTACTS)) return null
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return null
    }

    // ----- Hardware toggles -----
    fun setTorch(on: Boolean) {
        try {
            val id = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(id, on)
        } catch (e: Exception) {
            Logger.e(TAG, "Torch failed", e)
        }
    }

    fun openBluetoothSettings() = launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    fun openWifiSettings() = launch(Intent(Settings.ACTION_WIFI_SETTINGS))

    fun setVolume(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val value = (percent.coerceIn(0, 100) * max / 100)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, AudioManager.FLAG_SHOW_UI)
    }

    fun setBrightness(percent: Int) {
        if (!PermissionHelper.canWriteSettings(context)) {
            launch(PermissionHelper.writeSettingsIntent(context))
            return
        }
        val value = (percent.coerceIn(0, 100) * 255 / 100)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
    }

    // ----- Clock -----
    fun setAlarm(hour: Int, minute: Int, label: String) {
        launch(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        )
    }

    fun setTimer(seconds: Int, label: String) {
        launch(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        )
    }

    // ----- Media / navigation -----
    fun openCamera() = launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    fun openGallery() = launch(Intent(Intent.ACTION_VIEW).setType("image/*"))
    fun openMaps(query: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))))
    fun navigate(destination: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination))))
    fun openYouTube(query: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))))
    fun openSpotify() = openApp("spotify")
    fun playMusic() {
        launch(Intent("android.intent.action.MUSIC_PLAYER").addCategory(Intent.CATEGORY_APP_MUSIC))
    }

    // ----- Calendar -----
    fun addCalendarEvent(title: String, startMillis: Long) {
        launch(
            Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
        )
    }
    fun openCalendar() {
        val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
        ContentUris.appendId(builder, System.currentTimeMillis())
        launch(Intent(Intent.ACTION_VIEW).setData(builder.build()))
    }

    // ----- Utilities -----
    fun shareText(text: String) {
        launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))
    }
    fun takeScreenshot() = MyraAccessibilityService.instance?.takeScreenshotAction()

    /**
     * Execute a function call requested by the Gemini model. Returns a short
     * human-readable result that is sent back to the model so it can confirm
     * (or report failure of) the action out loud.
     */
    fun dispatch(name: String, args: JSONObject): String {
        return try {
            when (name) {
                "open_app" -> {
                    val app = args.optString("app_name")
                    if (openApp(app)) "Opened $app" else "$app is not installed on this phone"
                }
                "call_contact" -> { callContact(args.optString("name")); "Calling ${args.optString("name")}" }
                "send_whatsapp" -> whatsapp(args.optString("name"), args.optString("message"))
                "whatsapp_call" -> whatsappCall(args.optString("name"), false)
                "whatsapp_video_call" -> whatsappCall(args.optString("name"), true)
                "send_sms" -> sendSms(args.optString("name"), args.optString("message"))
                "send_email" -> { email(args.optString("to"), args.optString("subject"), args.optString("body")); "Email composer opened" }
                "set_torch" -> { val on = args.optBoolean("on", true); setTorch(on); "Torch " + if (on) "on" else "off" }
                "set_volume" -> { setVolume(args.optInt("percent")); "Volume set to ${args.optInt("percent")}%" }
                "set_brightness" -> { setBrightness(args.optInt("percent")); "Brightness set to ${args.optInt("percent")}%" }
                "set_alarm" -> { setAlarm(args.optInt("hour"), args.optInt("minute"), args.optString("label", "Alarm")); "Alarm set" }
                "set_timer" -> { setTimer(args.optInt("seconds"), args.optString("label", "Timer")); "Timer started" }
                "open_camera" -> { openCamera(); "Camera opened" }
                "open_gallery" -> { openGallery(); "Gallery opened" }
                "open_maps" -> { openMaps(args.optString("query")); "Maps opened" }
                "navigate" -> { navigate(args.optString("destination")); "Navigation started" }
                "search_youtube" -> { openYouTube(args.optString("query")); "YouTube opened" }
                "play_music" -> { playMusic(); "Music playing" }
                "open_url" -> { openChrome(args.optString("url")); "Opened link" }
                "open_settings" -> { openSettings(); "Settings opened" }
                "open_wifi_settings" -> { openWifiSettings(); "Wi-Fi settings opened" }
                "open_bluetooth_settings" -> { openBluetoothSettings(); "Bluetooth settings opened" }
                "take_screenshot" -> { takeScreenshot(); "Screenshot taken" }
                "lock_screen" -> if (MyraAccessibilityService.instance?.lockScreen() == true) "Screen locked" else "Turn on the Accessibility service to lock the screen"
                "go_home" -> if (MyraAccessibilityService.instance != null) { MyraAccessibilityService.instance?.goHome(); "Went to home screen" } else "Turn on the Accessibility service first"
                "go_back" -> if (MyraAccessibilityService.instance != null) { MyraAccessibilityService.instance?.goBack(); "Went back" } else "Turn on the Accessibility service first"
                "open_recents" -> if (MyraAccessibilityService.instance != null) { MyraAccessibilityService.instance?.openRecents(); "Opened recent apps" } else "Turn on the Accessibility service first"
                "open_notifications" -> if (MyraAccessibilityService.instance != null) { MyraAccessibilityService.instance?.openNotifications(); "Opened notifications" } else "Turn on the Accessibility service first"
                "get_weather" -> weather(args.optString("city"))
                "add_calendar_event" -> {
                    val millis = args.optString("start_epoch_millis").toLongOrNull() ?: System.currentTimeMillis()
                    addCalendarEvent(args.optString("title"), millis); "Calendar event created"
                }
                "share_text" -> { shareText(args.optString("text")); "Share sheet opened" }
                else -> "Unknown action: $name"
            }
        } catch (e: Exception) {
            Logger.e(TAG, "dispatch failed for $name", e)
            "Failed: ${e.message}"
        }
    }

    /**
     * Very small natural-language command layer. Scans the user's request for
     * an obvious device action; the spoken reply is what the user hears.
     */
    fun handleAssistantText(userText: String, assistantText: String) {
        val t = userText.lowercase(Locale.ROOT)
        when {
            t.contains("torch") || t.contains("flashlight") -> setTorch(!t.contains("off"))
            t.startsWith("open ") -> openApp(t.removePrefix("open ").trim())
            t.contains("call ") -> callContact(t.substringAfter("call ").trim())
            t.contains("screenshot") -> takeScreenshot()
            t.contains("camera") -> openCamera()
            t.contains("whatsapp") -> whatsapp(t.substringAfter("to ").trim(), assistantText)
        }
    }

    /** Extract a durable fact worth remembering (learning mode). */
    fun maybeLearn(userText: String): String? {
        val t = userText.lowercase(Locale.ROOT)
        return when {
            t.contains("my name is") -> userText.substringAfter("my name is").trim().let { "User's name is $it" }
            t.contains("i like") -> "User likes" + userText.substringAfter("i like")
            t.contains("remember that") -> userText.substringAfter("remember that").trim()
            else -> null
        }
    }

    companion object {
        private const val TAG = "PhoneController"
        // Default country code used to complete locally-saved numbers (Pakistan).
        private const val DEFAULT_COUNTRY_CODE = "92"
        private val KNOWN_APPS = mapOf(
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "chrome" to "com.android.chrome",
            "spotify" to "com.spotify.music",
            "youtube" to "com.google.android.youtube",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "play store" to "com.android.vending",
            "calculator" to "com.google.android.calculator",
            "camera" to "com.android.camera2",
            "settings" to "com.android.settings",
            "telegram" to "org.telegram.messenger",
            "snapchat" to "com.snapchat.android",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "messenger" to "com.facebook.orca",
            "tiktok" to "com.zhiliaoapp.musically",
            "clock" to "com.google.android.deskclock"
        )
    }
}

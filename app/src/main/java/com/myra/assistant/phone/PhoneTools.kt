package com.myra.assistant.phone

import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini Live API function declarations that expose MYRA's device-control
 * abilities to the model. When the model decides to act, it emits a toolCall
 * which VoiceSessionManager routes to [PhoneController.dispatch]. Using real
 * function calling (instead of parsing the spoken reply) makes device control
 * reliable and immediate.
 */
object PhoneTools {

    private fun fn(name: String, desc: String, params: JSONObject? = null): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        if (params != null) o.put("parameters", params)
        return o
    }

    private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val properties = JSONObject()
        props.forEach { properties.put(it.first, it.second) }
        val schema = JSONObject().put("type", "OBJECT").put("properties", properties)
        if (required.isNotEmpty()) schema.put("required", JSONArray(required))
        return schema
    }

    private fun str(desc: String) = JSONObject().put("type", "STRING").put("description", desc)
    private fun num(desc: String) = JSONObject().put("type", "INTEGER").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "BOOLEAN").put("description", desc)

    /** Returns the full Gemini Live `tools` array as a JSON string. */
    fun declarationsJson(): String {
        val decls = JSONArray()
        decls.put(fn("open_app", "Open an installed app by name, e.g. whatsapp, instagram, youtube, camera, settings.",
            obj("app_name" to str("The app name to open"), required = listOf("app_name"))))
        decls.put(fn("call_contact", "Start a phone call to a saved contact by name.",
            obj("name" to str("Contact name"), required = listOf("name"))))
        decls.put(fn("send_whatsapp", "Open a WhatsApp chat with a contact and prefill a message.",
            obj("name" to str("Contact name"), "message" to str("Message text"), required = listOf("name", "message"))))
        decls.put(fn("send_sms", "Open the SMS composer to a contact or number with a message.",
            obj("name" to str("Contact name or phone number"), "message" to str("Message text"), required = listOf("name", "message"))))
        decls.put(fn("send_email", "Compose an email.",
            obj("to" to str("Recipient email"), "subject" to str("Subject"), "body" to str("Body"), required = listOf("to"))))
        decls.put(fn("set_torch", "Turn the flashlight/torch on or off.",
            obj("on" to bool("true to turn on, false to turn off"), required = listOf("on"))))
        decls.put(fn("set_volume", "Set media volume as a percentage from 0 to 100.",
            obj("percent" to num("Volume percent 0-100"), required = listOf("percent"))))
        decls.put(fn("set_brightness", "Set screen brightness as a percentage from 0 to 100.",
            obj("percent" to num("Brightness percent 0-100"), required = listOf("percent"))))
        decls.put(fn("set_alarm", "Set an alarm for a specific time.",
            obj("hour" to num("Hour 0-23"), "minute" to num("Minute 0-59"), "label" to str("Alarm label"), required = listOf("hour", "minute"))))
        decls.put(fn("set_timer", "Start a countdown timer.",
            obj("seconds" to num("Duration in seconds"), "label" to str("Timer label"), required = listOf("seconds"))))
        decls.put(fn("open_camera", "Open the camera app."))
        decls.put(fn("open_gallery", "Open the photo gallery."))
        decls.put(fn("open_maps", "Search for a place on Google Maps.",
            obj("query" to str("Place to search"), required = listOf("query"))))
        decls.put(fn("navigate", "Start turn-by-turn navigation to a destination.",
            obj("destination" to str("Destination"), required = listOf("destination"))))
        decls.put(fn("search_youtube", "Search YouTube for a query.",
            obj("query" to str("Search query"), required = listOf("query"))))
        decls.put(fn("play_music", "Open the music player and play."))
        decls.put(fn("open_url", "Open a website, or search the web if it is not a URL.",
            obj("url" to str("URL or search text"), required = listOf("url"))))
        decls.put(fn("open_settings", "Open Android system settings."))
        decls.put(fn("open_wifi_settings", "Open Wi-Fi settings."))
        decls.put(fn("open_bluetooth_settings", "Open Bluetooth settings."))
        decls.put(fn("take_screenshot", "Take a screenshot (needs the accessibility service enabled)."))
        decls.put(fn("add_calendar_event", "Create a calendar event.",
            obj("title" to str("Event title"), "start_epoch_millis" to str("Start time as epoch milliseconds"), required = listOf("title"))))
        decls.put(fn("share_text", "Open the Android share sheet with some text.",
            obj("text" to str("Text to share"), required = listOf("text"))))
        decls.put(fn("remember", "Store a durable fact about the user to recall in future sessions.",
            obj("fact" to str("The fact to remember"), required = listOf("fact"))))
        decls.put(fn("whatsapp_call", "Start a WhatsApp voice call to a saved contact by name.",
            obj("name" to str("Contact name"), required = listOf("name"))))
        decls.put(fn("whatsapp_video_call", "Start a WhatsApp video call to a saved contact by name.",
            obj("name" to str("Contact name"), required = listOf("name"))))
        decls.put(fn("lock_screen", "Lock the phone screen (needs the accessibility service)."))
        decls.put(fn("go_home", "Go to the home screen."))
        decls.put(fn("go_back", "Press the system back button."))
        decls.put(fn("open_recents", "Open the recent apps screen."))
        decls.put(fn("open_notifications", "Open the notification shade."))
        decls.put(fn("get_weather", "Get the current weather. Leave city empty to use the current location.",
            obj("city" to str("City name, or empty for current location"))))

        val tools = JSONArray().put(JSONObject().put("functionDeclarations", decls))
        return tools.toString()
    }
}

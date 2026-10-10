package com.example.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class DeviceActionManager(private val context: Context) {

    companion object {
        private const val TAG = "DeviceActionManager"

        private val KNOWN_APP_PACKAGES = mapOf(
            "whatsapp" to "com.whatsapp",
            "whatsapp business" to "com.whatsapp.w4b",
            "youtube" to "com.google.android.youtube",
            "instagram" to "com.instagram.android",
            "spotify" to "com.spotify.music",
            "chrome" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "calculator" to "com.google.android.calculator",
            "play store" to "com.android.vending",
            "google play" to "com.android.vending"
        )

        private val WEB_FALLBACK_URLS = mapOf(
            "whatsapp" to "https://wa.me/",
            "youtube" to "https://www.youtube.com",
            "instagram" to "https://www.instagram.com",
            "spotify" to "https://open.spotify.com",
            "maps" to "https://maps.google.com",
            "gmail" to "https://mail.google.com"
        )
    }

    data class ContactMatch(val name: String, val number: String)

    fun openWhatsApp(): JSONObject {
        Log.d(TAG, "Executing openWhatsApp()")
        val pm = context.packageManager

        // Try direct launch intent for WhatsApp
        val launchIntent = pm.getLaunchIntentForPackage("com.whatsapp")
            ?: pm.getLaunchIntentForPackage("com.whatsapp.w4b")

        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            return JSONObject().apply {
                put("success", true)
                put("action", "openWhatsApp")
                put("message", "WhatsApp launched successfully")
            }
        }

        // Deep link web fallback
        return try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
            JSONObject().apply {
                put("success", true)
                put("action", "openWhatsApp")
                put("message", "Opened WhatsApp via link")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open WhatsApp", e)
            JSONObject().apply {
                put("success", false)
                put("action", "openWhatsApp")
                put("error", "WhatsApp is not installed and browser unavailable: ${e.message}")
            }
        }
    }

    fun openApp(appName: String): JSONObject {
        val cleanName = appName.trim().lowercase(Locale.ROOT)
        Log.d(TAG, "Executing openApp('$appName')")

        if (cleanName.contains("whatsapp")) {
            return openWhatsApp()
        }

        // Special system shortcuts
        if (cleanName == "settings" || cleanName.contains("setting")) {
            return try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                JSONObject().apply {
                    put("success", true)
                    put("appName", "Settings")
                    put("message", "Settings opened")
                }
            } catch (e: Exception) {
                JSONObject().apply {
                    put("success", false)
                    put("error", "Could not open settings: ${e.message}")
                }
            }
        }

        if (cleanName == "camera") {
            return try {
                val intent = Intent("android.media.action.IMAGE_CAPTURE").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                JSONObject().apply {
                    put("success", true)
                    put("appName", "Camera")
                    put("message", "Camera opened")
                }
            } catch (e: Exception) {
                JSONObject().apply {
                    put("success", false)
                    put("error", "Could not open camera: ${e.message}")
                }
            }
        }

        val pm = context.packageManager

        // Check known app packages
        val knownPackage = KNOWN_APP_PACKAGES[cleanName]
        if (knownPackage != null) {
            val intent = pm.getLaunchIntentForPackage(knownPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return JSONObject().apply {
                    put("success", true)
                    put("appName", appName)
                    put("message", "$appName opened successfully")
                }
            }
        }

        // Search installed apps by label
        try {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (appInfo in installedApps) {
                val label = pm.getApplicationLabel(appInfo).toString().lowercase(Locale.ROOT)
                if (label == cleanName || label.contains(cleanName)) {
                    val intent = pm.getLaunchIntentForPackage(appInfo.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return JSONObject().apply {
                            put("success", true)
                            put("appName", appInfo.packageName)
                            put("message", "$appName launched successfully")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving app $appName", e)
        }

        // Web fallback for popular platforms (YouTube, Instagram, Spotify, etc.)
        val webFallback = WEB_FALLBACK_URLS[cleanName]
        if (webFallback != null) {
            return openUrl(webFallback).apply {
                put("appName", appName)
                put("message", "Opened $appName in browser")
            }
        }

        return JSONObject().apply {
            put("success", false)
            put("appName", appName)
            put("error", "Application '$appName' is not installed on this device.")
        }
    }

    fun openUrl(url: String): JSONObject {
        Log.d(TAG, "Executing openUrl('$url')")
        var cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "https://$cleanUrl"
        }

        return try {
            val parsedUri = Uri.parse(cleanUrl)
            val intent = Intent(Intent.ACTION_VIEW, parsedUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            JSONObject().apply {
                put("success", true)
                put("url", cleanUrl)
                put("message", "Opened $cleanUrl in browser")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open url: $cleanUrl", e)
            JSONObject().apply {
                put("success", false)
                put("url", cleanUrl)
                put("error", "Could not open URL: ${e.message}")
            }
        }
    }

    fun makeCall(phoneNumber: String): JSONObject {
        Log.d(TAG, "Executing makeCall('$phoneNumber')")
        val cleanNumber = phoneNumber.replace(Regex("[^0+0-9]"), "").trim()
        if (cleanNumber.isEmpty()) {
            return JSONObject().apply {
                put("success", false)
                put("error", "Invalid or empty phone number")
            }
        }

        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            JSONObject().apply {
                put("success", true)
                put("phoneNumber", cleanNumber)
                put("message", "Opened phone dialer for $cleanNumber")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting dialer for $phoneNumber", e)
            JSONObject().apply {
                put("success", false)
                put("phoneNumber", cleanNumber)
                put("error", "Failed to start phone dialer: ${e.message}")
            }
        }
    }

    fun callContact(contactName: String): JSONObject {
        Log.d(TAG, "Executing callContact('$contactName')")
        val hasContactPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasContactPermission) {
            // Open dialer anyway so the user's action doesn't hit a dead end
            try {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}

            return JSONObject().apply {
                put("success", false)
                put("error", "Contacts permission needed to search '$contactName'. Dialpad opened.")
                put("permissionNeeded", "READ_CONTACTS")
            }
        }

        val matches = queryContactsByName(contactName)

        return when {
            matches.isEmpty() -> {
                // Open dialer so user can call directly
                try {
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}

                JSONObject().apply {
                    put("success", false)
                    put("contactName", contactName)
                    put("error", "No contact found matching '$contactName'. Opened dialpad.")
                }
            }
            matches.size == 1 -> {
                val match = matches.first()
                makeCall(match.number).apply {
                    put("contactName", match.name)
                }
            }
            else -> {
                val array = JSONArray()
                matches.take(5).forEach {
                    array.put(JSONObject().apply {
                        put("name", it.name)
                        put("number", it.number)
                    })
                }
                JSONObject().apply {
                    put("success", false)
                    put("multipleMatches", true)
                    put("error", "Found ${matches.size} contacts matching '$contactName'.")
                    put("contacts", array)
                }
            }
        }
    }

    private fun queryContactsByName(nameQuery: String): List<ContactMatch> {
        val results = mutableListOf<ContactMatch>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$nameQuery%")

        try {
            val cursor = context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                null
            )
            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = if (nameIndex >= 0) it.getString(nameIndex) else "Unknown"
                    val number = if (numberIndex >= 0) it.getString(numberIndex) else ""
                    if (number.isNotBlank()) {
                        results.add(ContactMatch(name, number))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts for $nameQuery", e)
        }
        return results.distinctBy { it.number }
    }
}

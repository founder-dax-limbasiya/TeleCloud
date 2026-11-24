package com.telecloud.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object TelegramApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    private fun endpoint(token: String, method: String): String {
        return "https://api.telegram.org/bot" + token + "/" + method
    }

    private fun readJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IOException("Telegram returned an empty response")
            val json = JSONObject(body)
            if (!response.isSuccessful || !json.optBoolean("ok", false)) {
                val description = json.optString("description", "Telegram request failed")
                throw IOException(description)
            }
            return json
        }
    }

    fun getMe(token: String): String {
        val request = Request.Builder().url(endpoint(token, "getMe")).get().build()
        val result = readJson(request).optJSONObject("result") ?: JSONObject()
        val username = result.optString("username", "connected bot")
        return "Connected successfully: @" + username
    }

    fun getUpdates(token: String, offset: Long): JSONObject {
        val url = endpoint(token, "getUpdates") + "?timeout=20&offset=" + offset
        val request = Request.Builder().url(url).get().build()
        return readJson(request)
    }

    fun sendMessage(token: String, chatId: String, message: String) {
        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", message)
            .build()
        val request = Request.Builder()
            .url(endpoint(token, "sendMessage"))
            .post(body)
            .build()
        readJson(request)
    }
}

class TelegramWorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private val channelId = "telecloud_worker"
    private val notificationId = 2410

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val vault = SecureVault(this)
        val token = vault.get("bot_token")
        val allowedChatId = vault.get("allowed_chat_id")
        if (token.isBlank() || allowedChatId.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(notificationId, buildNotification("Connected worker is waiting for Telegram commands"))
        pollingJob?.cancel()
        pollingJob = scope.launch { poll(token, allowedChatId) }
        return START_STICKY
    }

    /**
     * Android 15 enforces a rolling six-hour-per-24-hour budget for dataSync
     * foreground services when targeting API 35+. Stop cleanly when the OS
     * reports that budget has expired; this is not a mechanism for 24/7 work.
     */
    override fun onTimeout(startId: Int, foregroundServiceType: Int) {
        pollingJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun updateCursorKey(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        val fingerprint = digest.take(8).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return "last_handled_update_id_$fingerprint"
    }

    private fun nextUpdateOffset(token: String): Long {
        val lastHandled = getSharedPreferences("telecloud_telegram_state", MODE_PRIVATE)
            .getLong(updateCursorKey(token), -1L)
        return lastHandled + 1L
    }

    private fun acknowledgeUpdate(token: String, updateId: Long) {
        // Persist before the next getUpdates call acknowledges this update to Telegram.
        getSharedPreferences("telecloud_telegram_state", MODE_PRIVATE)
            .edit()
            .putLong(updateCursorKey(token), updateId)
            .commit()
    }

    private suspend fun poll(token: String, allowedChatId: String) {
        var offset = nextUpdateOffset(token)
        while (scope.isActive) {
            try {
                val response = withContext(Dispatchers.IO) {
                    TelegramApi.getUpdates(token, offset)
                }
                val updates = response.optJSONArray("result")
                if (updates != null) {
                    for (index in 0 until updates.length()) {
                        val update = updates.optJSONObject(index) ?: continue
                        val updateId = update.optLong("update_id", -1L)
                        if (updateId < 0L) continue

                        val message = update.optJSONObject("message")
                        val chat = message?.optJSONObject("chat")
                        val incomingChatId = chat?.optString("id").orEmpty()
                        if (incomingChatId == allowedChatId && message != null) {
                            val incomingText = message.optString("text").trim()
                            val commandToken = incomingText.substringBefore(" ")
                            val baseCommand = commandToken.substringBefore("@")
                            if (baseCommand.equals("/tc", ignoreCase = true)) {
                                val payload = incomingText.removePrefix(commandToken).trim()
                                val parts = payload.split(Regex("\\s+"), limit = 2)
                                val command = parts.firstOrNull()?.lowercase().orEmpty()
                                val arguments = parts.getOrNull(1).orEmpty().trim()
                                val reply = commandReply(command, arguments)
                                withContext(Dispatchers.IO) {
                                    TelegramApi.sendMessage(token, incomingChatId, reply)
                                }
                            }
                        }

                        acknowledgeUpdate(token, updateId)
                        offset = maxOf(offset, updateId + 1L)
                    }
                }
            } catch (_: Exception) {
                delay(4000L)
            }
        }
    }

    private fun commandReply(command: String, arguments: String): String {
        return when (command) {
            "", "help" -> {
                "TeleCloud commands:\n/tc status\n/tc projects\n/tc create <name>\n/tc project <name>\n/tc ping\n/tc help"
            }
            "status" -> {
                "TeleCloud worker is online on this Android device. This is a device worker, not a hosted VPS."
            }
            "projects" -> {
                val projects = ProjectStore(this).load()
                if (projects.isEmpty()) {
                    "No projects have been created on this device yet. Send /tc create <name> to add one."
                } else {
                    "Projects on this device:\n" + projects.take(35).joinToString("\n") { "- " + it.name }
                }
            }
            "create" -> createProject(arguments)
            "project" -> projectDetails(arguments)
            "ping" -> {
                "Pong. Phone worker received your command at " +
                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date()) + "."
            }
            else -> "Unknown command. Send /tc help for the available commands."
        }
    }

    private fun createProject(rawName: String): String {
        val name = rawName.trim()
        if (name.length !in 2..48 || name.any { it == '\n' || it == '\r' }) {
            return "Project name must be 2 to 48 characters.\nUsage: /tc create My Project"
        }

        val store = ProjectStore(this)
        val projects = store.load()
        if (projects.any { it.name.equals(name, ignoreCase = true) }) {
            return "A project named '$name' already exists. Send /tc projects to see your projects."
        }

        val project = TeleProject(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            description = "Created via Telegram",
            createdAt = System.currentTimeMillis(),
            keys = emptyList()
        )
        store.save(projects + project)
        return "Project created: $name\nYou can open it in TeleCloud to manage its details and API credentials."
    }

    private fun projectDetails(rawName: String): String {
        val name = rawName.trim()
        if (name.isBlank()) return "Usage: /tc project <name>"
        val project = ProjectStore(this).load().firstOrNull {
            it.name.equals(name, ignoreCase = true)
        } ?: return "Project '$name' was not found. Send /tc projects to see available projects."

        val description = project.description.ifBlank { "No description" }
        val created = java.text.DateFormat.getDateTimeInstance()
            .format(java.util.Date(project.createdAt))
        return "Project: \${project.name}\nDescription: $description\nAPI credentials: \${project.keys.size}\nCreated: $created"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "TeleCloud worker",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("TeleCloud worker active")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("TeleCloud worker active")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
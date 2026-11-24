package com.telecloud.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Ink = Color(0xFF0A0F18)
private val Panel = Color(0xFF121B29)
private val PanelRaised = Color(0xFF182437)
private val Accent = Color(0xFF8AB4F8)
private val Muted = Color(0xFF9BAAC0)
private val Good = Color(0xFF83D6B0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val store = remember { ProjectStore(this) }
            val vault = remember { SecureVault(this) }
            TeleCloudApp(store, vault)
        }
    }
}

data class ApiCredential(
    val id: String,
    val label: String,
    val prefix: String,
    val hash: String,
    val createdAt: Long
)

data class TeleProject(
    val id: String,
    val name: String,
    val description: String,
    val createdAt: Long,
    val keys: List<ApiCredential>
)

class ProjectStore(context: Context) {
    private val prefs = context.getSharedPreferences("telecloud_projects", Context.MODE_PRIVATE)

    fun load(): List<TeleProject> {
        return try {
            val array = JSONArray(prefs.getString("projects", "[]") ?: "[]")
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val keyArray = item.optJSONArray("keys") ?: JSONArray()
                    val keys = buildList {
                        for (j in 0 until keyArray.length()) {
                            val key = keyArray.getJSONObject(j)
                            add(ApiCredential(
                                id = key.optString("id"),
                                label = key.optString("label"),
                                prefix = key.optString("prefix"),
                                hash = key.optString("hash"),
                                createdAt = key.optLong("createdAt")
                            ))
                        }
                    }
                    add(TeleProject(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        description = item.optString("description"),
                        createdAt = item.optLong("createdAt"),
                        keys = keys
                    ))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(projects: List<TeleProject>) {
        val array = JSONArray()
        projects.forEach { project ->
            val keyArray = JSONArray()
            project.keys.forEach { key ->
                keyArray.put(JSONObject()
                    .put("id", key.id)
                    .put("label", key.label)
                    .put("prefix", key.prefix)
                    .put("hash", key.hash)
                    .put("createdAt", key.createdAt))
            }
            array.put(JSONObject()
                .put("id", project.id)
                .put("name", project.name)
                .put("description", project.description)
                .put("createdAt", project.createdAt)
                .put("keys", keyArray))
        }
        prefs.edit().putString("projects", array.toString()).apply()
    }
}

class SecureVault(context: Context) {
    private val prefs = context.getSharedPreferences("telecloud_secure", Context.MODE_PRIVATE)
    private val alias = "telecloud_android_vault_v1"

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    fun put(name: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String {
        return try {
            val encoded = prefs.getString(name, null) ?: return ""
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= 12) return ""
            val iv = payload.copyOfRange(0, 12)
            val encrypted = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}

object ApiKeyFactory {
    fun generate(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return "tc_live_" + Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

private enum class Tab { HOME, PROJECTS, TELEGRAM, GITHUB }

@Composable
private fun TeleCloudApp(store: ProjectStore, vault: SecureVault) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.HOME) }
    var projects by remember { mutableStateOf(store.load()) }
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var generatedSecret by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("") }

    fun refresh() { projects = store.load() }
    fun saveProject(project: TeleProject) {
        store.save(projects.map { if (it.id == project.id) project else it })
        refresh()
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent,
        secondary = Good,
        background = Ink,
        surface = Panel,
        onBackground = Color(0xFFF0F4FA),
        onSurface = Color(0xFFF0F4FA)
    )) {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            Column(modifier = Modifier.fillMaxSize()) {
                val selected = projects.firstOrNull { it.id == selectedProjectId }
                if (selected != null && tab == Tab.PROJECTS) {
                    ProjectDetailScreen(
                        project = selected,
                        onBack = { selectedProjectId = null },
                        onGenerate = { label ->
                            val secret = ApiKeyFactory.generate()
                            val credential = ApiCredential(
                                id = java.util.UUID.randomUUID().toString(),
                                label = label.ifBlank { "Default key" },
                                prefix = secret.take(18),
                                hash = ApiKeyFactory.hash(secret),
                                createdAt = System.currentTimeMillis()
                            )
                            saveProject(selected.copy(keys = selected.keys + credential))
                            generatedSecret = secret
                        },
                        onRevoke = { keyId ->
                            saveProject(selected.copy(keys = selected.keys.filterNot { it.id == keyId }))
                        }
                    )
                } else {
                    when (tab) {
                        Tab.HOME -> DashboardScreen(
                            projects = projects,
                            onOpenProjects = { tab = Tab.PROJECTS },
                            onCreate = { showCreate = true },
                            onOpenProject = {
                                selectedProjectId = it
                                tab = Tab.PROJECTS
                            }
                        )
                        Tab.PROJECTS -> ProjectsScreen(
                            projects = projects,
                            onCreate = { showCreate = true },
                            onOpenProject = { selectedProjectId = it }
                        )
                        Tab.GITHUB -> GitHubScreen(vault = vault)
                        Tab.TELEGRAM -> TelegramScreen(
                            vault = vault,
                            statusMessage = statusMessage,
                            onStatus = { statusMessage = it },
                            onTest = { token ->
                                scope.launch {
                                    statusMessage = "Checking Telegram bot..."
                                    statusMessage = try {
                                        withContext(Dispatchers.IO) { TelegramApi.getMe(token) }
                                    } catch (e: Exception) {
                                        "Connection failed: " + (e.message ?: "Unknown error")
                                    }
                                }
                            }
                        )
                    }
                    NavigationBar(containerColor = Panel, contentColor = Muted) {
                        NavigationBarItem(
                            selected = tab == Tab.HOME,
                            onClick = { tab = Tab.HOME; selectedProjectId = null },
                            icon = { Icon(Icons.Outlined.Home, contentDescription = "Home") },
                            label = { Text("Home") }
                        )
                        NavigationBarItem(
                            selected = tab == Tab.PROJECTS,
                            onClick = { tab = Tab.PROJECTS; selectedProjectId = null },
                            icon = { Icon(Icons.Outlined.Folder, contentDescription = "Projects") },
                            label = { Text("Projects") }
                        )
                        NavigationBarItem(
                            selected = tab == Tab.GITHUB,
                            onClick = { tab = Tab.GITHUB; selectedProjectId = null },
                            icon = { Icon(Icons.Outlined.Code, contentDescription = "GitHub Actions") },
                            label = { Text("GitHub") }
                        )
                        NavigationBarItem(
                            selected = tab == Tab.TELEGRAM,
                            onClick = { tab = Tab.TELEGRAM; selectedProjectId = null },
                            icon = { Icon(Icons.Outlined.Send, contentDescription = "Telegram") },
                            label = { Text("Telegram") }
                        )
                    }
                }
            }
        }

        if (showCreate) {
            CreateProjectDialog(
                onDismiss = { showCreate = false },
                onCreate = { name, description ->
                    val project = TeleProject(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name.trim(),
                        description = description.trim(),
                        createdAt = System.currentTimeMillis(),
                        keys = emptyList()
                    )
                    store.save(projects + project)
                    refresh()
                    showCreate = false
                }
            )
        }

        generatedSecret?.let { secret ->
            AlertDialog(
                onDismissRequest = { generatedSecret = null },
                containerColor = PanelRaised,
                title = { Text("Copy your new API key") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("This secret is shown once. TeleCloud stores only its SHA-256 hash on this device.")
                        Text(secret, color = Good, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "This is currently a locally managed credential. A remote API gateway must be connected before it can authenticate external API requests.",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TeleCloud API key", secret))
                        generatedSecret = null
                    }) { Text("Copy and close") }
                },
                dismissButton = { TextButton(onClick = { generatedSecret = null }) { Text("Close") } }
            )
        }
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(42.dp).background(Accent.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Cloud, contentDescription = null, tint = Accent)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    projects: List<TeleProject>,
    onOpenProjects: () -> Unit,
    onCreate: () -> Unit,
    onOpenProject: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader("TeleCloud", "Your project control room")
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 18.dp, end = 18.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = PanelRaised), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("TELECLOUD CORE", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                            Spacer(Modifier.weight(1f))
                            Text("ANDROID WORKER", color = Good, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("One place for every project.", fontSize = 25.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
                        Text(
                            "Manage project records, create API credentials and connect a Telegram bot to this phone.",
                            color = Muted,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                        FilledTonalButton(onClick = onOpenProjects) {
                            Icon(Icons.Outlined.Folder, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Manage projects")
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("Projects", projects.size.toString(), Modifier.weight(1f))
                    StatCard("API keys", projects.sumOf { it.keys.size }.toString(), Modifier.weight(1f))
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Terminal, contentDescription = null, tint = Accent)
                            Spacer(Modifier.width(9.dp))
                            Text("Phone worker", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text("Not started", color = Muted, fontSize = 11.sp)
                        }
                        Text(
                            "The phone can poll your Telegram bot for TeleCloud commands while its worker service is running. It is not an always-on VPS.",
                            color = Muted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Your projects", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCreate) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text("New")
                    }
                }
            }
            if (projects.isEmpty()) {
                item { EmptyState("Nothing here yet", "Create your first project. It will get its own API-key vault.") }
            } else {
                items(projects.take(4), key = { it.id }) { project ->
                    ProjectCard(project = project, onClick = { onOpenProject(project.id) })
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(value, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Accent)
            Text(label, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ProjectsScreen(
    projects: List<TeleProject>,
    onCreate: () -> Unit,
    onOpenProject: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader("Projects", "Separate workspaces on this device")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("All projects", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Button(onClick = onCreate) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("Create")
            }
        }
        if (projects.isEmpty()) {
            EmptyState("Create a project", "For example: FRAMEFLOW AI, NRD AI or CLIPRUSH.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(project = project, onClick = { onOpenProject(project.id) })
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: TeleProject, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Accent.copy(alpha = 0.13f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = Accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(project.name, fontWeight = FontWeight.SemiBold)
                Text(project.description.ifBlank { "No description added" }, color = Muted, fontSize = 12.sp, maxLines = 1)
                Text(project.keys.size.toString() + " API key(s)", color = Accent, fontSize = 11.sp)
            }
            Icon(Icons.Outlined.ArrowBack, contentDescription = null, tint = Muted)
        }
    }
}

@Composable
private fun ProjectDetailScreen(
    project: TeleProject,
    onBack: () -> Unit,
    onGenerate: (String) -> Unit,
    onRevoke: (String) -> Unit
) {
    var label by remember(project.id) { mutableStateOf("Production key") }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Back")
            }
            Spacer(Modifier.width(12.dp))
            Text("Project details", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = PanelRaised), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(project.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(project.description.ifBlank { "No description" }, color = Muted, fontSize = 13.sp)
                        Text("Created " + java.text.DateFormat.getDateInstance().format(java.util.Date(project.createdAt)), color = Muted, fontSize = 11.sp)
                    }
                }
            }
            item {
                Text("API credentials", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Generate a separate secret for this project. The raw secret is displayed once; only its SHA-256 hash is kept on this phone.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
            item {
                OutlinedTextField(value = label, onValueChange = { label = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Key label") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onGenerate(label); label = "New key" }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Key, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Generate API key")
                }
            }
            if (project.keys.isEmpty()) {
                item { EmptyState("No API keys yet", "Generate a key for this project to create its first local credential.") }
            } else {
                items(project.keys, key = { it.id }) { key ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Key, contentDescription = null, tint = Accent)
                                Spacer(Modifier.width(8.dp))
                                Text(key.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onRevoke(key.id) }) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = Color(0xFFFF9A9A))
                                    Text("Revoke", color = Color(0xFFFF9A9A))
                                }
                            }
                            Text(key.prefix + "••••••••", color = Good, fontSize = 12.sp)
                            Text("Created " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(key.createdAt)), color = Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Text(
                        "These keys are stored in this device's local vault. They are not yet accepted by a remote server; request authentication will become active when a compatible TeleCloud API gateway is added.",
                        modifier = Modifier.padding(14.dp),
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateProjectDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelRaised,
        title = { Text("Create project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Project name") }, singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description (optional)") })
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name, description) }, enabled = name.isNotBlank()) {
                Text("Create project")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EmptyState(title: String, message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(message, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun TelegramScreen(
    vault: SecureVault,
    statusMessage: String,
    onStatus: (String) -> Unit,
    onTest: (String) -> Unit
) {
    val context = LocalContext.current
    var token by remember { mutableStateOf(vault.get("bot_token")) }
    var allowedChatId by remember { mutableStateOf(vault.get("allowed_chat_id")) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader("Telegram", "Connect the bot that carries worker commands")
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = PanelRaised), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Send, tint = Accent, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Bot connection", fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Create a bot with @BotFather. The token is encrypted with Android Keystore on this phone. Add your own Telegram chat ID as the only allowed controller.",
                            color = Muted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Telegram bot token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            }
            item {
                OutlinedTextField(
                    value = allowedChatId,
                    onValueChange = { allowedChatId = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Allowed chat ID") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
            item {
                Button(
                    onClick = {
                        vault.put("bot_token", token.trim())
                        vault.put("allowed_chat_id", allowedChatId.trim())
                        onStatus("Credentials encrypted and saved on this device.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = token.isNotBlank() && allowedChatId.isNotBlank()
                ) {
                    Icon(Icons.Outlined.Shield, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save securely")
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        vault.put("bot_token", token.trim())
                        if (token.isNotBlank()) onTest(token.trim()) else onStatus("Enter your bot token first.")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Test bot connection")
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            vault.put("bot_token", token.trim())
                            vault.put("allowed_chat_id", allowedChatId.trim())
                            ContextCompat.startForegroundService(context, Intent(context, TelegramWorkerService::class.java))
                            onStatus("Worker start requested. Check the persistent TeleCloud notification.")
                        },
                        modifier = Modifier.weight(1f),
                        enabled = token.isNotBlank() && allowedChatId.isNotBlank()
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Text("Start worker")
                    }
                    OutlinedButton(
                        onClick = {
                            context.stopService(Intent(context, TelegramWorkerService::class.java))
                            onStatus("Worker stop requested.")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Stop, contentDescription = null)
                        Text("Stop")
                    }
                }
            }
            if (statusMessage.isNotBlank()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                        Text(statusMessage, modifier = Modifier.padding(14.dp), color = Good, fontSize = 12.sp)
                    }
                }
            }
            item {
                Text("Telegram commands", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                CommandRow("/tc help", "Show available commands")
                CommandRow("/tc status", "Check whether this phone worker responds")
                CommandRow("/tc projects", "List projects saved on this phone")
                CommandRow("/tc create <name>", "Create a project from Telegram")
                CommandRow("/tc project <name>", "View project details and key count")
                CommandRow("/tc ping", "Check the bot-to-phone response")
            }
            item {
                Text(
                    "Keep the app's battery setting unrestricted for more reliable polling. Android may still stop background work, and this phone is not a 24/7 cloud VPS. Only messages from the allowed chat ID are processed.",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
private fun CommandRow(command: String, description: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(command, color = Good, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(142.dp))
        Text(description, color = Muted, fontSize = 11.sp)
    }
}

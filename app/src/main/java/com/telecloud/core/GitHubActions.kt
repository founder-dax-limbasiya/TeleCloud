package com.telecloud.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private val GitHubPanel = Color(0xFF121B29)
private val GitHubRaised = Color(0xFF182437)
private val GitHubAccent = Color(0xFF8AB4F8)
private val GitHubMuted = Color(0xFF9BAAC0)
private val GitHubGood = Color(0xFF83D6B0)
private val GitHubBad = Color(0xFFFF9A9A)

data class GitHubWorkflow(
    val id: Long,
    val name: String,
    val path: String,
    val state: String
)

data class GitHubRun(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String,
    val branch: String,
    val event: String,
    val createdAt: String,
    val htmlUrl: String
)

object GitHubActionsApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun request(
        token: String,
        path: String,
        method: String = "GET",
        body: JSONObject? = null
    ): JSONObject {
        val builder = Request.Builder()
            .url("https://api.github.com$path")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        when (method) {
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(jsonType))
            else -> builder.get()
        }

        client.newCall(builder.build()).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = try {
                    JSONObject(responseText).optString("message", "GitHub request failed")
                } catch (_: Exception) {
                    "GitHub request failed"
                }
                throw IOException("GitHub API ${response.code}: $detail")
            }
            return if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
        }
    }

    private fun repositoryPath(owner: String, repo: String): String {
        val safePart = Regex("^[A-Za-z0-9_.-]{1,100}$")
        require(safePart.matches(owner) && safePart.matches(repo)) {
            "Enter the repository as owner/repo using its GitHub name."
        }
        return "/repos/$owner/$repo"
    }

    fun listWorkflows(token: String, owner: String, repo: String): List<GitHubWorkflow> {
        val data = request(token, "${repositoryPath(owner, repo)}/actions/workflows?per_page=100")
        val array = data.optJSONArray("workflows") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    GitHubWorkflow(
                        id = item.optLong("id"),
                        name = item.optString("name", "Unnamed workflow"),
                        path = item.optString("path"),
                        state = item.optString("state")
                    )
                )
            }
        }.filter { it.id > 0L }
    }

    fun listRecentRuns(token: String, owner: String, repo: String): List<GitHubRun> {
        val data = request(token, "${repositoryPath(owner, repo)}/actions/runs?per_page=12")
        val array = data.optJSONArray("workflow_runs") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    GitHubRun(
                        id = item.optLong("id"),
                        name = item.optString("name", "Workflow run"),
                        status = item.optString("status", "unknown"),
                        conclusion = item.optString("conclusion"),
                        branch = item.optString("head_branch"),
                        event = item.optString("event"),
                        createdAt = item.optString("created_at"),
                        htmlUrl = item.optString("html_url")
                    )
                )
            }
        }
    }

    fun dispatch(token: String, owner: String, repo: String, workflowId: Long, ref: String) {
        require(ref.isNotBlank()) { "Enter a branch name before running a workflow." }
        val body = JSONObject().put("ref", ref.trim())
        request(
            token = token,
            path = "${repositoryPath(owner, repo)}/actions/workflows/$workflowId/dispatches",
            method = "POST",
            body = body
        )
    }
}

@Composable
fun GitHubScreen(vault: SecureVault) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(vault.get("github_token")) }
    var repository by remember { mutableStateOf(vault.get("github_repo")) }
    var branch by remember { mutableStateOf("main") }
    var workflows by remember { mutableStateOf<List<GitHubWorkflow>>(emptyList()) }
    var runs by remember { mutableStateOf<List<GitHubRun>>(emptyList()) }
    var connectedRepo by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var messageIsError by remember { mutableStateOf(false) }

    fun parseRepository(): Pair<String, String>? {
        val normalized = repository.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removeSuffix(".git")
            .trim('/')
        val parts = normalized.split("/")
        if (parts.size != 2) return null
        return parts[0] to parts[1]
    }

    fun loadRepository() {
        val parts = parseRepository()
        if (token.isBlank()) {
            message = "Add a GitHub fine-grained token first."
            messageIsError = true
            return
        }
        if (parts == null) {
            message = "Enter a repository as owner/repo."
            messageIsError = true
            return
        }
        vault.put("github_token", token.trim())
        vault.put("github_repo", parts.first + "/" + parts.second)
        isBusy = true
        message = "Loading workflows and recent runs..."
        messageIsError = false
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val loadedWorkflows = GitHubActionsApi.listWorkflows(
                        token.trim(), parts.first, parts.second
                    )
                    val loadedRuns = GitHubActionsApi.listRecentRuns(
                        token.trim(), parts.first, parts.second
                    )
                    loadedWorkflows to loadedRuns
                }
                workflows = result.first
                runs = result.second
                connectedRepo = parts.first + "/" + parts.second
                message = if (workflows.isEmpty()) {
                    "Connected. No GitHub Actions workflows were found in this repository."
                } else {
                    "Connected to $connectedRepo."
                }
            } catch (error: Exception) {
                message = error.message ?: "Could not connect to GitHub."
                messageIsError = true
            } finally {
                isBusy = false
            }
        }
    }

    fun runWorkflow(workflow: GitHubWorkflow) {
        val parts = parseRepository()
        if (parts == null || token.isBlank()) {
            message = "Connect to a repository before starting a workflow."
            messageIsError = true
            return
        }
        if (workflow.state != "active") {
            message = "This workflow is disabled in GitHub. Enable it there before running."
            messageIsError = true
            return
        }
        isBusy = true
        message = "Sending workflow dispatch..."
        messageIsError = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    GitHubActionsApi.dispatch(
                        token.trim(), parts.first, parts.second, workflow.id, branch
                    )
                }
                message = "Dispatch accepted by GitHub. Refreshing run history..."
                delay(1600)
                runs = withContext(Dispatchers.IO) {
                    GitHubActionsApi.listRecentRuns(
                        token.trim(), parts.first, parts.second
                    )
                }
            } catch (error: Exception) {
                message = error.message ?: "Could not start this workflow."
                messageIsError = true
            } finally {
                isBusy = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 10.dp, bottom = 22.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("GitHub Actions", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "Control your repository builds from this phone.",
                    fontSize = 12.sp,
                    color = GitHubMuted
                )
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = GitHubRaised),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Connect a repository", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Use a fine-grained GitHub personal access token limited to the repository you want to control.",
                        color = GitHubMuted,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("GitHub token") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = repository,
                        onValueChange = { repository = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Repository (owner/repo)") },
                        placeholder = { Text("example/my-project") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = branch,
                        onValueChange = { branch = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Branch for workflow runs") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { loadRepository() },
                            enabled = !isBusy && token.isNotBlank() && repository.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.width(18.dp).height(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (connectedRepo.isBlank()) "Connect" else "Refresh")
                        }
                        OutlinedButton(
                            onClick = {
                                vault.put("github_token", "")
                                vault.put("github_repo", "")
                                token = ""
                                repository = ""
                                connectedRepo = ""
                                workflows = emptyList()
                                runs = emptyList()
                                message = "GitHub connection removed from this device."
                                messageIsError = false
                            },
                            enabled = !isBusy
                        ) {
                            Text("Forget")
                        }
                    }
                    Text(
                        "Required fine-grained token permissions: Actions read and write. Metadata read is included by GitHub. The token is encrypted with Android Keystore on this phone.",
                        color = GitHubMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }
        if (message.isNotBlank()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = GitHubPanel),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        message,
                        modifier = Modifier.padding(14.dp),
                        color = if (messageIsError) GitHubBad else GitHubGood,
                        fontSize = 12.sp
                    )
                }
            }
        }
        if (connectedRepo.isNotBlank()) {
            item {
                Text(
                    "Workflows · $connectedRepo",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            if (workflows.isEmpty()) {
                item {
                    Text(
                        "No workflows found. Add a workflow YAML file under .github/workflows in this repository.",
                        color = GitHubMuted,
                        fontSize = 12.sp
                    )
                }
            } else {
                items(workflows, key = { it.id }) { workflow ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = GitHubPanel),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text(workflow.name, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(
                                workflow.path + " · " + workflow.state,
                                color = GitHubMuted,
                                fontSize = 11.sp
                            )
                            Button(
                                onClick = { runWorkflow(workflow) },
                                enabled = !isBusy && workflow.state == "active" && branch.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Run on $branch")
                            }
                        }
                    }
                }
            }
            item {
                Text("Recent runs", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            if (runs.isEmpty()) {
                item {
                    Text("No workflow runs found yet.", color = GitHubMuted, fontSize = 12.sp)
                }
            } else {
                items(runs, key = { it.id }) { run ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = GitHubPanel),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(run.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                            val resultLabel = if (run.status == "completed") {
                                run.conclusion.ifBlank { "completed" }
                            } else {
                                run.status
                            }
                            Text(
                                resultLabel.uppercase() + " · " + run.branch + " · " + run.event,
                                color = when (resultLabel) {
                                    "success" -> GitHubGood
                                    "failure", "cancelled", "timed_out" -> GitHubBad
                                    else -> GitHubAccent
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(run.createdAt, color = GitHubMuted, fontSize = 10.sp)
                        }
                    }
                }
            }
            item {
                Text(
                    "A workflow can be started only when its YAML includes the workflow_dispatch trigger. A successful dispatch request means GitHub accepted it; the run may still fail while executing.",
                    color = GitHubMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

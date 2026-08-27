package com.mice.nebulamcp

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** GitHub REST MCP tools backed by the user-configured GitHub PAT. */
class GithubTools(private val settings: SettingsStore) {
    private val apiBase = "https://api.github.com"

    fun descriptors(): List<JSONObject> {
        fun t(name: String, desc: String, props: JSONObject, required: List<String> = emptyList(), write: Boolean = false): JSONObject {
            val schema = JSONObject().put("type", "object").put("properties", props).put("additionalProperties", false)
            if (required.isNotEmpty()) schema.put("required", JSONArray(required))
            val o = JSONObject().put("name", name).put("description", desc).put("inputSchema", schema)
            o.put("annotations", JSONObject()
                .put("readOnlyHint", !write)
                .put("destructiveHint", write)
                .put("openWorldHint", true))
            return o
        }
        val s = mutableListOf<JSONObject>()
        s += t("github_whoami", "Return the authenticated GitHub account. Requires a configured GitHub token.", JSONObject())
        s += t("github_list_repositories", "List repositories visible to the authenticated user.", JSONObject()
            .put("visibility", JSONObject().put("type", "string").put("enum", JSONArray(listOf("all", "public", "private"))))
            .put("type", JSONObject().put("type", "string").put("enum", JSONArray(listOf("all", "owner", "member"))))
            .put("perPage", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 100)))
        s += t("github_get_repository", "Get repository metadata.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string")), listOf("owner", "repo"))
        s += t("github_list_branches", "List branches in a repository.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("perPage", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 100)), listOf("owner", "repo"))
        s += t("github_list_commits", "List recent commits for a repository or branch.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("sha", JSONObject().put("type", "string"))
            .put("perPage", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 100)), listOf("owner", "repo"))
        s += t("github_get_file", "Read a file from a repository. Returns decoded UTF-8 text plus metadata; binary files return base64.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("path", JSONObject().put("type", "string"))
            .put("ref", JSONObject().put("type", "string")), listOf("owner", "repo", "path"))
        s += t("github_list_directory", "List files and directories at a repository path.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("path", JSONObject().put("type", "string"))
            .put("ref", JSONObject().put("type", "string")), listOf("owner", "repo"))
        s += t("github_write_file", "Create or update one repository file. content is plain text unless base64=true. Creates a commit.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("path", JSONObject().put("type", "string"))
            .put("content", JSONObject().put("type", "string"))
            .put("message", JSONObject().put("type", "string"))
            .put("branch", JSONObject().put("type", "string"))
            .put("sha", JSONObject().put("type", "string"))
            .put("base64", JSONObject().put("type", "boolean")), listOf("owner", "repo", "path", "content", "message"), write = true)
        s += t("github_delete_file", "Delete one repository file and create a commit. Requires the file SHA.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("path", JSONObject().put("type", "string"))
            .put("message", JSONObject().put("type", "string"))
            .put("sha", JSONObject().put("type", "string"))
            .put("branch", JSONObject().put("type", "string")), listOf("owner", "repo", "path", "message", "sha"), write = true)
        s += t("github_create_branch", "Create a branch from a commit SHA or an existing branch name.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("branch", JSONObject().put("type", "string"))
            .put("from", JSONObject().put("type", "string")), listOf("owner", "repo", "branch", "from"), write = true)
        s += t("github_list_issues", "List repository issues; GitHub may include pull requests in this endpoint.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("state", JSONObject().put("type", "string").put("enum", JSONArray(listOf("open", "closed", "all"))))
            .put("perPage", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 100)), listOf("owner", "repo"))
        s += t("github_create_issue", "Create a GitHub issue.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("title", JSONObject().put("type", "string"))
            .put("body", JSONObject().put("type", "string"))
            .put("labels", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))), listOf("owner", "repo", "title"), write = true)
        s += t("github_update_issue", "Update an issue title/body/state/labels.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("number", JSONObject().put("type", "integer"))
            .put("title", JSONObject().put("type", "string"))
            .put("body", JSONObject().put("type", "string"))
            .put("state", JSONObject().put("type", "string").put("enum", JSONArray(listOf("open", "closed"))))
            .put("labels", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))), listOf("owner", "repo", "number"), write = true)
        s += t("github_comment_issue", "Add a comment to an issue or pull request.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("number", JSONObject().put("type", "integer"))
            .put("body", JSONObject().put("type", "string")), listOf("owner", "repo", "number", "body"), write = true)
        s += t("github_list_pull_requests", "List pull requests in a repository.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("state", JSONObject().put("type", "string").put("enum", JSONArray(listOf("open", "closed", "all"))))
            .put("perPage", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 100)), listOf("owner", "repo"))
        s += t("github_create_pull_request", "Create a pull request from head into base.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("title", JSONObject().put("type", "string"))
            .put("head", JSONObject().put("type", "string"))
            .put("base", JSONObject().put("type", "string"))
            .put("body", JSONObject().put("type", "string")), listOf("owner", "repo", "title", "head", "base"), write = true)
        s += t("github_get_pull_request", "Get one pull request with branch and mergeability metadata.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("number", JSONObject().put("type", "integer")), listOf("owner", "repo", "number"))
        s += t("github_get_commit", "Get one commit and its file/status metadata.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("sha", JSONObject().put("type", "string")), listOf("owner", "repo", "sha"))
        s += t("github_delete_branch", "Delete a branch from a repository.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("branch", JSONObject().put("type", "string")), listOf("owner", "repo", "branch"), write = true)
        s += t("github_list_releases", "List repository releases.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("perPage", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 100)), listOf("owner", "repo"))
        s += t("github_create_release", "Create a GitHub release for a tag or target branch/commit.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("tag", JSONObject().put("type", "string"))
            .put("name", JSONObject().put("type", "string"))
            .put("body", JSONObject().put("type", "string"))
            .put("target", JSONObject().put("type", "string"))
            .put("draft", JSONObject().put("type", "boolean"))
            .put("prerelease", JSONObject().put("type", "boolean")), listOf("owner", "repo", "tag"), write = true)
        s += t("github_list_workflows", "List GitHub Actions workflow files.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string")), listOf("owner", "repo"))
        s += t("github_run_workflow", "Dispatch a GitHub Actions workflow on a branch or tag.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("workflow", JSONObject().put("type", "string"))
            .put("ref", JSONObject().put("type", "string"))
            .put("inputs", JSONObject().put("type", "object")), listOf("owner", "repo", "workflow", "ref"), write = true)
        s += t("github_merge_pull_request", "Merge a pull request using the selected merge method.", JSONObject()
            .put("owner", JSONObject().put("type", "string"))
            .put("repo", JSONObject().put("type", "string"))
            .put("number", JSONObject().put("type", "integer"))
            .put("method", JSONObject().put("type", "string").put("enum", JSONArray(listOf("merge", "squash", "rebase"))))
            .put("commitTitle", JSONObject().put("type", "string"))
            .put("commitMessage", JSONObject().put("type", "string")), listOf("owner", "repo", "number"), write = true)
        s += t("github_search_repositories", "Search public and accessible repositories using GitHub search syntax.", JSONObject()
            .put("q", JSONObject().put("type", "string"))
            .put("perPage", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 50)), listOf("q"))
        s += t("github_search_code", "Search code in repositories accessible to the token.", JSONObject()
            .put("q", JSONObject().put("type", "string"))
            .put("perPage", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 50)), listOf("q"))
        return s
    }

    fun call(name: String, a: JSONObject): JSONObject {
        if (settings.githubToken.isBlank()) return err("GitHub Token 未配置，请先在 MCP 设置中配置 GitHub Token")
        return try {
            when (name) {
                "github_whoami" -> get("/user")
                "github_list_repositories" -> get("/user/repos?visibility=${enc(a.optString("visibility", "all"))}&type=${enc(a.optString("type", "all"))}&per_page=${a.optInt("perPage", 100).coerceIn(1,100)}")
                "github_get_repository" -> get(repoPath(a) )
                "github_list_branches" -> get(repoPath(a) + "/branches?per_page=${a.optInt("perPage",100).coerceIn(1,100)}")
                "github_list_commits" -> {
                    val q = if (a.optString("sha").isNotBlank()) "?sha=${enc(a.getString("sha"))}&per_page=${a.optInt("perPage",30).coerceIn(1,100)}" else "?per_page=${a.optInt("perPage",30).coerceIn(1,100)}"
                    get(repoPath(a) + "/commits$q")
                }
                "github_get_file" -> get(repoPath(a) + "/contents/" + path(a.getString("path")) + refQuery(a))
                "github_list_directory" -> get(repoPath(a) + "/contents/" + path(a.optString("path")) + refQuery(a))
                "github_write_file" -> writeFile(a)
                "github_delete_file" -> deleteFile(a)
                "github_create_branch" -> createBranch(a)
                "github_list_issues" -> get(repoPath(a) + "/issues?state=${enc(a.optString("state","open"))}&per_page=${a.optInt("perPage",50).coerceIn(1,100)}")
                "github_create_issue" -> post(repoPath(a) + "/issues", JSONObject().apply {
                    put("title", a.getString("title")); if (a.has("body")) put("body", a.getString("body")); if (a.has("labels")) put("labels", a.getJSONArray("labels"))
                })
                "github_update_issue" -> {
                    val body = JSONObject(); listOf("title","body","state","labels").forEach { if (a.has(it)) body.put(it, a.get(it)) }
                    patch(repoPath(a) + "/issues/" + a.getInt("number"), body)
                }
                "github_comment_issue" -> post(repoPath(a) + "/issues/" + a.getInt("number") + "/comments", JSONObject().put("body", a.getString("body")))
                "github_list_pull_requests" -> get(repoPath(a) + "/pulls?state=${enc(a.optString("state","open"))}&per_page=${a.optInt("perPage",50).coerceIn(1,100)}")
                "github_create_pull_request" -> post(repoPath(a) + "/pulls", JSONObject().apply {
                    put("title", a.getString("title")); put("head", a.getString("head")); put("base", a.getString("base")); if (a.has("body")) put("body", a.getString("body"))
                })
                "github_get_pull_request" -> get(repoPath(a) + "/pulls/" + a.getInt("number"))
                "github_get_commit" -> get(repoPath(a) + "/commits/" + path(a.getString("sha")))
                "github_delete_branch" -> request("DELETE", repoPath(a) + "/git/refs/heads/" + path(a.getString("branch")), null)
                "github_list_releases" -> get(repoPath(a) + "/releases?per_page=${a.optInt("perPage",30).coerceIn(1,100)}")
                "github_create_release" -> post(repoPath(a) + "/releases", JSONObject().apply {
                    put("tag_name", a.getString("tag")); if (a.has("name")) put("name", a.getString("name")); if (a.has("body")) put("body", a.getString("body")); if (a.has("target")) put("target_commitish", a.getString("target")); if (a.has("draft")) put("draft", a.getBoolean("draft")); if (a.has("prerelease")) put("prerelease", a.getBoolean("prerelease"))
                })
                "github_list_workflows" -> get(repoPath(a) + "/actions/workflows")
                "github_run_workflow" -> post(repoPath(a) + "/actions/workflows/" + path(a.getString("workflow")) + "/dispatches", JSONObject().apply {
                    put("ref", a.getString("ref")); if (a.has("inputs")) put("inputs", a.getJSONObject("inputs"))
                })
                "github_merge_pull_request" -> put(repoPath(a) + "/pulls/" + a.getInt("number") + "/merge", JSONObject().apply {
                    put("merge_method", a.optString("method","merge")); if (a.has("commitTitle")) put("commit_title", a.getString("commitTitle")); if (a.has("commitMessage")) put("commit_message", a.getString("commitMessage"))
                })
                "github_search_repositories" -> get("/search/repositories?q=${enc(a.getString("q"))}&per_page=${a.optInt("perPage",30).coerceIn(1,50)}")
                "github_search_code" -> get("/search/code?q=${enc(a.getString("q"))}&per_page=${a.optInt("perPage",30).coerceIn(1,50)}")
                else -> err("unknown GitHub tool: $name")
            }
        } catch (e: Exception) { err(e.message ?: e.toString()) }
    }

    private fun repoPath(a: JSONObject) = "/repos/${enc(a.getString("owner"))}/${enc(a.getString("repo"))}"
    private fun refQuery(a: JSONObject): String = if (a.optString("ref").isNotBlank()) "?ref=${enc(a.getString("ref"))}" else ""
    private fun path(p: String): String = p.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { enc(it) }
    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun writeFile(a: JSONObject): JSONObject {
        val raw = a.getString("content")
        val encoded = if (a.optBoolean("base64", false)) raw else Base64.encodeToString(raw.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val body = JSONObject().put("message", a.getString("message")).put("content", encoded)
        if (a.optString("branch").isNotBlank()) body.put("branch", a.getString("branch"))
        if (a.optString("sha").isNotBlank()) body.put("sha", a.getString("sha"))
        return put(repoPath(a) + "/contents/" + path(a.getString("path")), body)
    }

    private fun deleteFile(a: JSONObject): JSONObject {
        val body = JSONObject().put("message", a.getString("message")).put("sha", a.getString("sha"))
        if (a.optString("branch").isNotBlank()) body.put("branch", a.getString("branch"))
        return request("DELETE", repoPath(a) + "/contents/" + path(a.getString("path")), body)
    }

    private fun createBranch(a: JSONObject): JSONObject {
        val from = a.getString("from")
        val sha = if (Regex("^[0-9a-fA-F]{7,64}$").matches(from)) from else {
            val branch = get(repoPath(a) + "/git/ref/heads/" + path(from))
            branch.optJSONObject("object")?.optString("sha") ?: throw IllegalStateException("cannot resolve source branch")
        }
        return post(repoPath(a) + "/git/refs", JSONObject().put("ref", "refs/heads/${a.getString("branch")}").put("sha", sha))
    }

    private fun get(path: String): JSONObject = request("GET", path, null)
    private fun post(path: String, body: JSONObject): JSONObject = request("POST", path, body)
    private fun patch(path: String, body: JSONObject): JSONObject = request("PATCH", path, body)
    private fun put(path: String, body: JSONObject): JSONObject = request("PUT", path, body)

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val conn = (URL(apiBase + path).openConnection() as HttpURLConnection)
        conn.requestMethod = method
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("Authorization", "Bearer ${settings.githubToken}")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        }
        val code = conn.responseCode
        val input = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = input?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        if (code !in 200..299) {
            val detail = try { JSONObject(text).optString("message", text) } catch (_: Exception) { text }
            throw IllegalStateException("GitHub HTTP $code: $detail")
        }
        return if (text.isBlank()) JSONObject().put("ok", true) else JSONObject(text)
    }

    private fun err(message: String) = JSONObject().put("content", JSONArray().put(JSONObject().put("type","text").put("text", message))).put("isError", true)
}

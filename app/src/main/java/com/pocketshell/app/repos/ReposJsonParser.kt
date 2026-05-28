package com.pocketshell.app.repos

import org.json.JSONArray
import org.json.JSONObject

public class ReposJsonParser {
    public fun parseList(raw: String): List<RepoEntry> {
        val array = try {
            JSONArray(raw)
        } catch (t: Throwable) {
            throw IllegalArgumentException("repos JSON is not an array", t)
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                    ?: throw IllegalArgumentException("repo entry $index is not an object")
                add(parseEntry(item, index))
            }
        }
    }

    private fun parseEntry(item: JSONObject, index: Int): RepoEntry {
        val name = item.stringOrNull("name")
            ?: throw IllegalArgumentException("repo entry $index is missing name")
        return RepoEntry(
            owner = item.stringOrNull("owner"),
            name = name,
            fullName = item.stringOrNull("full_name"),
            local = item.objectOrNull("local")?.let(::parseLocal),
            remote = item.objectOrNull("remote")?.let(::parseRemote),
        )
    }

    private fun parseLocal(item: JSONObject): LocalRepoInfo {
        val path = item.stringOrNull("path")
            ?: throw IllegalArgumentException("repo local entry is missing path")
        return LocalRepoInfo(
            path = path,
            head = item.stringOrNull("head"),
        )
    }

    private fun parseRemote(item: JSONObject): RemoteRepoInfo =
        RemoteRepoInfo(
            defaultBranch = item.stringOrNull("default_branch"),
            htmlUrl = item.stringOrNull("html_url"),
            sshUrl = item.stringOrNull("ssh_url"),
            updatedAt = item.stringOrNull("updated_at"),
        )

    private fun JSONObject.stringOrNull(name: String): String? =
        when (val value = opt(name)) {
            null, JSONObject.NULL -> null
            is String -> value.takeIf { it.isNotBlank() }
            else -> null
        }

    private fun JSONObject.objectOrNull(name: String): JSONObject? =
        opt(name) as? JSONObject
}

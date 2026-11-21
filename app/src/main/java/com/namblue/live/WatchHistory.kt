package com.namblue.live

import android.content.Context

/**
 * Recently watched "Other" TikTok usernames, newest first, persisted in SharedPreferences.
 *
 * The list logic ([added], [removed], [encode], [decode]) is pure so it can be unit-tested
 * without Android; [load], [record] and [delete] are the thin persistence wrappers.
 */
object WatchHistory {

    const val MAX_ENTRIES = 12

    private const val PREFS_NAME = "watch_history"
    private const val KEY_USERNAMES = "usernames"
    private const val SEPARATOR = "\n"

    fun load(context: Context): List<String> =
        decode(prefs(context).getString(KEY_USERNAMES, null))

    /** Save [username] as the most recently watched; returns the updated list. */
    fun record(context: Context, username: String): List<String> =
        added(load(context), username).also { save(context, it) }

    /** Remove [username] from the history; returns the updated list. */
    fun delete(context: Context, username: String): List<String> =
        removed(load(context), username).also { save(context, it) }

    fun added(history: List<String>, username: String): List<String> {
        val name = username.trim()
        if (name.isEmpty()) return history
        val rest = history.filterNot { it.equals(name, ignoreCase = true) }
        return (listOf(name) + rest).take(MAX_ENTRIES)
    }

    fun removed(history: List<String>, username: String): List<String> =
        history.filterNot { it.equals(username, ignoreCase = true) }

    fun encode(history: List<String>): String = history.joinToString(SEPARATOR)

    fun decode(raw: String?): List<String> =
        raw.orEmpty().split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    private fun save(context: Context, history: List<String>) {
        prefs(context).edit().putString(KEY_USERNAMES, encode(history)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

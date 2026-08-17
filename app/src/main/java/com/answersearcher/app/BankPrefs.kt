package com.answersearcher.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 持久化「选择题库」的结果：文件路径（Uri 字符串）、题目列/答案列、是否含表头、显示名。
 * 这样下次启动 App 可自动重新加载，无需每次手动选择。
 */
object BankPrefs {

    private const val NAME = "bank_prefs"
    private const val KEY_URI = "bank_uri"
    private const val KEY_Q = "bank_q"
    private const val KEY_A = "bank_a"
    private const val KEY_HEADER = "bank_header"
    private const val KEY_FILE = "bank_file"

    data class BankPref(
        val uri: String,
        val qCol: Int,
        val aCol: Int,
        val hasHeader: Boolean,
        val fileName: String
    )

    fun save(context: Context, pref: BankPref) {
        prefs(context).edit().apply {
            putString(KEY_URI, pref.uri)
            putInt(KEY_Q, pref.qCol)
            putInt(KEY_A, pref.aCol)
            putBoolean(KEY_HEADER, pref.hasHeader)
            putString(KEY_FILE, pref.fileName)
            apply()
        }
    }

    fun load(context: Context): BankPref? {
        val p = prefs(context)
        val uri = p.getString(KEY_URI, null) ?: return null
        return BankPref(
            uri = uri,
            qCol = p.getInt(KEY_Q, 0),
            aCol = p.getInt(KEY_A, 1),
            hasHeader = p.getBoolean(KEY_HEADER, true),
            fileName = p.getString(KEY_FILE, "") ?: ""
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}

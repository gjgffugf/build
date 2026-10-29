package com.yuanbao.gradlebuilder

import android.content.Context

object Prefs {

    private const val NAME = "gradle_builder"
    private const val K_TREE = "tree_uri"
    private const val K_CMD = "custom_command"
    private const val K_REPO = "repo_url"
    private const val K_SDK = "android_sdk"
    private const val K_ENV = "env_ready"
    private const val K_PROJECT = "project_name"

    private val sp get() = App.ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var treeUri: String?
        get() = sp.getString(K_TREE, null)
        set(v) = sp.edit().putString(K_TREE, v).apply()

    var command: String?
        get() = sp.getString(K_CMD, null)
        set(v) = sp.edit().putString(K_CMD, v).apply()

    var repo: String?
        get() = sp.getString(K_REPO, null)
        set(v) = sp.edit().putString(K_REPO, v).apply()

    var sdk: String?
        get() = sp.getString(K_SDK, null)
        set(v) = sp.edit().putString(K_SDK, v).apply()

    var projectName: String?
        get() = sp.getString(K_PROJECT, null)
        set(v) = sp.edit().putString(K_PROJECT, v).apply()

    var envReady: Boolean
        get() = sp.getBoolean(K_ENV, false)
        set(v) = sp.edit().putBoolean(K_ENV, v).apply()
}

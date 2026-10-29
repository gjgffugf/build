package com.yuanbao.gradlebuilder

import java.io.File

/**
 * 设备本地的 Linux（Termux 风格）运行环境。
 * 所有真实构建都发生在这个真实文件系统路径下，native 进程可直接访问。
 */
object Env {

    /** 相当于 Termux 的 $PREFIX */
    val prefix: File get() = File(App.ctx.filesDir, "usr")

    /** 相当于 Termux 的 $HOME */
    val home: File get() = File(App.ctx.filesDir, "home")

    val tmp: File get() = File(prefix, "tmp")

    val workspace: File get() = File(App.ctx.filesDir, "workspace")

    /** 被构建项目的真实路径 */
    val project: File get() = File(workspace, "project")

    val bash: File get() = File(prefix, "bin/bash")

    val gradleBin: File get() = File(prefix, "bin/gradle")

    val javaBin: File get() = File(prefix, "bin/java")

    fun isInstalled(): Boolean = bash.exists() && gradleBin.exists() && javaBin.exists()

    fun hasBootstrap(): Boolean = bash.exists()

    /** 自动探测 JDK 安装位置（Termux 的 openjdk-xx 位于 $PREFIX/opt/openjdk-xx） */
    fun javaHome(): String {
        val opt = File(prefix, "opt")
        val jdk = opt.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("openjdk") }
            ?.sortedByDescending { it.name }
            ?.firstOrNull { File(it, "bin/java").exists() }
        return jdk?.absolutePath ?: prefix.absolutePath
    }

    fun sdkHome(): String {
        val custom = Prefs.sdk?.trim().orEmpty()
        if (custom.isNotEmpty()) return custom
        return File(home, "android-sdk").absolutePath
    }

    fun baseEnv(): Map<String, String> {
        val p = prefix.absolutePath
        val h = home.absolutePath
        val env = linkedMapOf(
            "PREFIX" to p,
            "HOME" to h,
            "TMPDIR" to File(p, "tmp").absolutePath,
            "PATH" to "$p/bin:$p/bin/applets:/system/bin:/system/xbin:/system/sbin:/vendor/bin",
            "LD_LIBRARY_PATH" to "$p/lib",
            "LANG" to "en_US.UTF-8",
            "TERM" to "xterm-256color",
            "TMP" to File(p, "tmp").absolutePath,
            "GRADLE_USER_HOME" to "$h/.gradle",
            "JAVA_HOME" to javaHome(),
            "ANDROID_HOME" to sdkHome(),
            "ANDROID_SDK_ROOT" to sdkHome(),
            "ANDROID_ROOT" to "/system",
            "ANDROID_DATA" to "/data",
            "EXTERNAL_STORAGE" to "/sdcard"
        )
        return env
    }

    fun prepareDirs() {
        prefix.mkdirs()
        home.mkdirs()
        tmp.mkdirs()
        workspace.mkdirs()
        File(home, ".gradle").mkdirs()
    }
}

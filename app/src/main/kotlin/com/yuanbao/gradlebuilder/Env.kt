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

    /** 实际可用的 shell，按优先级依次找 bash → sh → ash → busybox → mksh */
    fun shell(): File {
        for (name in listOf("bash", "sh", "ash", "busybox", "mksh")) {
            val f = File(prefix, "bin/$name")
            if (f.exists() && f.length() > 0) return f
        }
        return bash
    }

    /** bash 用 -lc，ash 用 -c 并手动加载 profile */
    fun shellArgs(script: String): List<String> {
        val s = shell()
        return if (s.name == "bash") listOf("-lc", script)
        else listOf("-c", ". ${prefix.absolutePath}/etc/profile 2>/dev/null; $script")
    }

    /** 符号链接丢失时也能找到 gradle */
    fun gradleBin(): File {
        val direct = File(prefix, "bin/gradle")
        if (direct.exists()) return direct
        findBinDir("gradle")?.let { File(it, "bin/gradle") }?.takeIf { it.exists() }
            ?.let { return it }
        return direct
    }

    fun javaBin(): File {
        val direct = File(prefix, "bin/java")
        if (direct.exists()) return direct
        File(javaHome(), "bin/java").takeIf { it.exists() }?.let { return it }
        return direct
    }

    fun isInstalled(): Boolean =
        shell().exists() && shell().length() > 0 && gradleBin().exists() && javaBin().exists()

    fun hasBootstrap(): Boolean = shell().exists() && shell().length() > 0

    /** 自动探测 JDK 安装位置（Termux 的 openjdk-xx 位于 $PREFIX/opt/openjdk-xx） */
    fun javaHome(): String {
        val opt = File(prefix, "opt")
        opt.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("openjdk") }
            ?.sortedByDescending { it.name }
            ?.firstOrNull { File(it, "bin/java").exists() }
            ?.let { return it.absolutePath }
        return findBinDir("java")?.absolutePath ?: prefix.absolutePath
    }

    /** 在 PREFIX 下 BFS 找含 bin/<name> 的目录 */
    private fun findBinDir(name: String): File? {
        val q = ArrayDeque<Pair<File, Int>>()
        q.add(prefix to 0)
        while (q.isNotEmpty()) {
            val (dir, depth) = q.removeFirst()
            if (File(dir, "bin/$name").exists()) return dir
            if (depth >= 3) continue
            dir.listFiles()
                ?.filter { it.isDirectory && it.name != "bin" && it.name != "home" }
                ?.forEach { q.add(it to depth + 1) }
        }
        return null
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
            "PATH" to listOf(
                "$p/bin",
                "$p/bin/applets",
                gradleBin().parent ?: "$p/bin",
                "${javaHome()}/bin",
                "/system/bin", "/system/xbin", "/system/sbin", "/vendor/bin"
            ).distinct().joinToString(":"),
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
            "EXTERNAL_STORAGE" to "/sdcard",
            "DEBIAN_FRONTEND" to "noninteractive",
            "ANDROID_ART_ROOT" to "/apex/com.android.art",
            "ANDROID_I18N_ROOT" to "/apex/com.android.i18n",
            "ANDROID_TZDATA_ROOT" to "/apex/com.android.tzdata"
        )
        return env
    }

    /** 新版 bootstrap 自带的第二阶段初始化脚本 */
    fun secondStageScript(): File? {
        val f = File(prefix, "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh")
        return if (f.exists()) f else null
    }

    fun prepareDirs() {
        prefix.mkdirs()
        home.mkdirs()
        tmp.mkdirs()
        workspace.mkdirs()
        File(home, ".gradle").mkdirs()
    }
}

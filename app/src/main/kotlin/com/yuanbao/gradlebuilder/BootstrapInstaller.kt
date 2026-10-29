package com.yuanbao.gradlebuilder

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 下载并解压 Termux 官方 bootstrap，在 App 私有目录中搭出一个真实的 Linux 用户态。
 *
 * 正确来源（注意不是 termux-app）：
 *   https://github.com/termux/termux-packages/releases/download/
 *     bootstrap-2025.12.21-r1+apt.android-7/bootstrap-aarch64.zip
 * tag 里的 "+" 必须编码成 %2B，否则 404。
 */
object BootstrapInstaller {

    private const val API_LATEST =
        "https://api.github.com/repos/termux/termux-packages/releases/latest"

    private const val RELEASE_PATH =
        "https://github.com/termux/termux-packages/releases/download"

    /** 动态取不到最新版本时的兜底 tag（由新到旧） */
    private val FALLBACK_TAGS = listOf(
        "bootstrap-2026.10.25-r1+apt.android-7",
        "bootstrap-2025.12.21-r1+apt.android-7",
        "bootstrap-2025.11.23-r1+apt.android-7"
    )

    /** 下载镜像前缀，第一个是直连 GitHub，后面是常用加速镜像 */
    private val MIRRORS = listOf(
        "",
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://gh-proxy.net/",
        "https://ghproxy.net/",
        "https://mirror.ghproxy.com/"
    )

    @Volatile
    var abort = false

    fun abiName(): String {
        for (abi in Build.SUPPORTED_ABIS) {
            when (abi) {
                "arm64-v8a" -> return "aarch64"
                "armeabi-v7a", "armeabi" -> return "arm"
                "x86_64" -> return "x86_64"
                "x86" -> return "i686"
            }
        }
        return "aarch64"
    }

    private fun assetUrl(tag: String): String =
        "$RELEASE_PATH/${tag.replace("+", "%2B")}/bootstrap-${abiName()}.zip"

    /** 取最新 tag，失败返回 null */
    private fun fetchLatestTag(): String? = runCatching {
        (URL(API_LATEST).openConnection() as HttpURLConnection).run {
            instanceFollowRedirects = true
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            connect()
            val code = responseCode
            if (code !in 200..299) return null
            val body = inputStream.bufferedReader().readText()
            JSONObject(body).optString("tag_name").takeIf { it.startsWith("bootstrap-") }
        }
    }.getOrNull()

    suspend fun ensureInstalled(onLog: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        abort = false
        if (Env.hasBootstrap()) {
            onLog("[环境] 基础运行环境已存在，跳过下载")
            Env.prepareDirs()
            return@withContext true
        }

        val custom = Prefs.bootstrapUrl?.trim().orEmpty()
        val latest = fetchLatestTag()
        val tags = buildList {
            if (latest != null) add(latest)
            FALLBACK_TAGS.forEach { add(it) }
        }.distinct()
        onLog(
            if (latest != null) "[版本] 最新 bootstrap：$latest"
            else "[版本] 无法查询最新版本，使用内置版本列表"
        )

        // 先直连 GitHub 逐个版本试，再换加速镜像
        val candidates = buildList {
            if (custom.isNotEmpty()) add(custom)
            tags.forEach { add(assetUrl(it)) }
            MIRRORS.drop(1).forEach { add(it + assetUrl(tags.first())) }
        }.distinct()

        val zipFile = File(App.ctx.cacheDir, "bootstrap-${abiName()}.zip")
        var downloaded = false
        for (url in candidates) {
            if (abort) return@withContext false
            onLog("[下载] $url")
            if (download(url, zipFile, onLog)) {
                downloaded = true
                break
            }
            onLog("[重试] 换下一个下载源…")
        }
        if (!downloaded) {
            onLog("[失败] 所有下载源均不可用。可改用主界面「导入运行环境(离线)」手动导入 bootstrap zip。")
            return@withContext false
        }

        onLog("[解压] 正在写入运行环境…")
        return@withContext try {
            unzip(zipFile, App.ctx.filesDir, onLog)
            normalizeLayout(onLog)
            applySymlinks(File(App.ctx.filesDir, "SYMLINKS.txt"), onLog)
            applyExecutables(File(App.ctx.filesDir, "EXECUTABLES.txt"), onLog)
            chmodExecutables(onLog)
            Env.prepareDirs()
            Env.bash.setExecutable(true)
            if (!Env.hasBootstrap()) {
                onLog("[失败] 解压后未找到 ${Env.bash}，bootstrap 包可能不完整")
                return@withContext false
            }
            onLog("[完成] 基础运行环境就绪")
            true
        } catch (e: Exception) {
            onLog("[失败] ${e.message}")
            false
        }
    }

    /** 离线导入：直接用用户选中的 zip */
    suspend fun importZip(src: File, onLog: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            unzip(src, App.ctx.filesDir, onLog)
            normalizeLayout(onLog)
            applySymlinks(File(App.ctx.filesDir, "SYMLINKS.txt"), onLog)
            applyExecutables(File(App.ctx.filesDir, "EXECUTABLES.txt"), onLog)
            chmodExecutables(onLog)
            Env.prepareDirs()
            Env.bash.setExecutable(true)
            Env.hasBootstrap().also {
                onLog(if (it) "[完成] 运行环境导入成功" else "[失败] 包内未找到 usr/bin/bash")
            }
        } catch (e: Exception) {
            onLog("[失败] ${e.message}")
            false
        }
    }

    private fun download(urlStr: String, target: File, onLog: (String) -> Unit): Boolean {
        var conn: HttpURLConnection? = null
        var out: FileOutputStream? = null
        return try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 12_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connect()
            if (conn.responseCode !in 200..299) {
                onLog("[失败] HTTP ${conn.responseCode}")
                return false
            }
            val len = conn.getHeaderFieldLong("Content-Length", -1L)
            if (len in 1..200_000) {
                onLog("[失败] 返回内容过小，不是有效的 bootstrap 包")
                return false
            }
            out = FileOutputStream(target)
            val buf = ByteArray(64 * 1024)
            var done = 0L
            var lastPct = -1
            while (!abort) {
                val n = conn.inputStream.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                done += n
                if (len > 0) {
                    val pct = (done * 100 / len).toInt()
                    if (pct / 10 != lastPct / 10) {
                        lastPct = pct
                        onLog("[下载] $pct% (${done / 1024} KB)")
                    }
                }
            }
            out.flush()
            if (abort) {
                onLog("[取消] 下载已中止")
                return false
            }
            onLog("[下载] 完成，共 ${done / 1024} KB")
            true
        } catch (e: Exception) {
            onLog("[失败] ${e.message}")
            false
        } finally {
            runCatching { out?.close() }
            runCatching { conn?.disconnect() }
        }
    }

    private fun unzip(zip: File, destDir: File, onLog: (String) -> Unit) {
        val destPath = destDir.canonicalPath
        var count = 0
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                val name = entry!!.name
                val outFile = File(destDir, name)
                val outPath = outFile.canonicalPath
                if (!outPath.startsWith(destPath)) throw IOException("非法压缩包路径：$name")
                if (entry!!.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zis.copyTo(it) }
                    count++
                }
                zis.closeEntry()
            }
        }
        onLog("[解压] 写入 $count 个文件")
    }

    /** 不同版本 bootstrap 的顶层目录可能是 usr/ 或 files/usr/，统一成 filesDir/usr */
    private fun normalizeLayout(onLog: (String) -> Unit) {
        val root = App.ctx.filesDir
        if (File(root, "usr/bin/bash").exists()) return
        val nested = listOf(
            File(root, "files"),
            File(root, "data/data/com.termux/files")
        ).firstOrNull { File(it, "usr/bin/bash").exists() }
            ?: return
        onLog("[整理] 归一化目录结构：${nested.name}/usr -> usr")
        nested.listFiles()?.forEach { f ->
            val dest = File(root, f.name)
            if (dest.exists()) dest.deleteRecursively()
            f.renameTo(dest)
        }
        nested.deleteRecursively()
    }

    /**
     * SYMLINKS.txt：新版格式为 "目标←链接路径"（← 是 U+2190），
     * 兼容旧版用 ":" 分隔的写法。链接路径相对 PREFIX。
     */
    private fun applySymlinks(file: File, onLog: (String) -> Unit) {
        if (!file.exists()) {
            onLog("[警告] 包内无 SYMLINKS.txt")
            return
        }
        var count = 0
        file.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val parts = when {
                line.contains("←") -> line.split("←", limit = 2)
                line.contains(":") -> line.split(":", limit = 2)
                else -> return@forEach
            }
            if (parts.size != 2) return@forEach
            val targetRaw = parts[0].trim()
            val linkRaw = parts[1].trim()
            val target = Paths.get(
                if (targetRaw.startsWith("/")) targetRaw
                else File(Env.prefix, targetRaw).absolutePath
            )
            val link = File(Env.prefix, linkRaw)
            try {
                link.parentFile?.mkdirs()
                if (link.exists() || Files.isSymbolicLink(link.toPath())) link.delete()
                Files.createSymbolicLink(link.toPath(), target)
                count++
            } catch (e: Exception) {
                // 个别链接失败不影响整体
            }
        }
        onLog("[链接] 建立符号链接 $count 个")
    }

    /** EXECUTABLES.txt：每行是相对 PREFIX 的路径，需要 0700 */
    private fun applyExecutables(file: File, onLog: (String) -> Unit) {
        if (!file.exists()) return
        var count = 0
        file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { rel ->
            val f = File(Env.prefix, rel)
            if (f.exists()) {
                runCatching { f.setExecutable(true, true); f.setReadable(true, true) }
                count++
            }
        }
        onLog("[权限] 已按 EXECUTABLES.txt 授权 $count 个文件")
    }

    private fun chmodExecutables(onLog: (String) -> Unit) {
        val roots = listOf(
            File(Env.prefix, "bin"),
            File(Env.prefix, "libexec"),
            File(Env.prefix, "bin/applets"),
            File(Env.prefix, "lib/apt/apt-helper"),
            File(Env.prefix, "lib/apt/methods")
        )
        roots.filter { it.exists() }.forEach { root ->
            if (root.isFile) root.setExecutable(true)
            else root.walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true, false) }
        }
        onLog("[权限] 已赋予可执行文件权限")
    }
}

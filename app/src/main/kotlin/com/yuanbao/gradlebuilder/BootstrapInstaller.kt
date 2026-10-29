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
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 下载并解压 Termux 官方 bootstrap，在 App 私有目录中搭出一个真实的 Linux 用户态。
 *
 * 正确来源（注意不是 termux-app）：
 *   https://github.com/termux/termux-packages/releases/download/
 *     bootstrap-2026.10.25-r1%2Bapt.android-7/bootstrap-aarch64.zip
 * tag 里的 "+" 必须编码成 %2B，否则 404。
 *
 * 解压要点：新版 bootstrap 把符号链接作为真实 symlink entry 存进 zip，
 * Java 的 ZipInputStream 会把它解成 0 字节空文件，因此优先调用系统 unzip，
 * 失败再回退 Java 解压 + 清理占位 + 按 SYMLINKS.txt 重建。
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

    private fun fetchLatestTag(): String? = runCatching {
        (URL(API_LATEST).openConnection() as HttpURLConnection).run {
            instanceFollowRedirects = true
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            connect()
            if (responseCode !in 200..299) return null
            val body = inputStream.bufferedReader().readText()
            JSONObject(body).optString("tag_name").takeIf { it.startsWith("bootstrap-") }
        }
    }.getOrNull()

    suspend fun ensureInstalled(onLog: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        abort = false
        if (ready()) {
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

        val candidates = buildList {
            if (custom.isNotEmpty()) add(custom)
            tags.forEach { add(assetUrl(it)) }
            MIRRORS.drop(1).forEach { add(it + assetUrl(tags.first())) }
        }.distinct()

        val zipFile = File(App.ctx.cacheDir, "bootstrap-${abiName()}.zip")
        for (url in candidates) {
            if (abort) return@withContext false
            onLog("[下载] $url")

            if (!download(url, zipFile, onLog)) {
                onLog("[重试] 下载失败，换下一个源…")
                continue
            }
            if (!verifyZip(zipFile, onLog)) {
                onLog("[重试] 压缩包损坏（下载不完整），换下一个源…")
                zipFile.delete()
                continue
            }
            onLog("[解压] 正在写入运行环境…")
            if (installFrom(zipFile, onLog)) {
                onLog("[完成] 基础运行环境就绪")
                return@withContext true
            }
            onLog("[重试] 解压后校验不通过，清理并换下一个源…")
            wipePrefix()
            zipFile.delete()
        }

        onLog("[失败] 所有下载源均不可用。")
        onLog("[建议] 1) 主界面填可用的 bootstrap 直链；2) 或用「导入运行环境(离线)」选中电脑上下载好的 zip。")
        onLog("[诊断] ${diagnose()}")
        false
    }

    /** 离线导入：直接用用户选中的 zip */
    suspend fun importZip(src: File, onLog: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (!verifyZip(src, onLog)) {
            onLog("[失败] zip 校验不通过，文件可能已损坏")
            return@withContext false
        }
        wipePrefix()
        val ok = installFrom(src, onLog)
        onLog(if (ok) "[完成] 运行环境导入成功" else "[诊断] ${diagnose()}")
        ok
    }

    /** 下载 → 解压 → 归一化 → 修链接 → 修权限 → 校验 bash */
    private fun installFrom(zip: File, onLog: (String) -> Unit): Boolean {
        val root = App.ctx.filesDir
        return try {
            root.mkdirs()
            if (!trySystemUnzip(zip, root, onLog)) {
                onLog("[解压] 系统 unzip 不可用，改用 Java 解压")
                unzipJava(zip, root, onLog)
            }
            normalizeLayout(onLog)
            relocateMetaFiles(onLog)
            dropEmptyPlaceholders(onLog)
            applySymlinks(locateMeta("SYMLINKS.txt"), onLog)
            applyExecutables(locateMeta("EXECUTABLES.txt"), onLog)
            chmodExecutables(onLog)
            Env.prepareDirs()
            repairBash(onLog)
            if (!ready()) {
                onLog("[失败] 未找到可用的 shell")
                return false
            }
            onLog("[校验] shell = ${Env.shell().absolutePath} (${Env.shell().length()} bytes)")
            true
        } catch (e: Exception) {
            onLog("[失败] ${e.message}")
            false
        }
    }

    private fun ready(): Boolean {
        val s = Env.shell()
        return s.exists() && s.length() > 0 && (s.canExecute() || s.setExecutable(true))
    }

    private fun wipePrefix() {
        runCatching { Env.prefix.deleteRecursively() }
        runCatching { File(App.ctx.filesDir, "home").deleteRecursively() }
        listOf("SYMLINKS.txt", "EXECUTABLES.txt").forEach {
            runCatching { File(App.ctx.filesDir, it).delete() }
        }
    }

    // ---------------- 下载 ----------------

    private fun download(urlStr: String, target: File, onLog: (String) -> Unit): Boolean {
        var conn: HttpURLConnection? = null
        var out: FileOutputStream? = null
        return try {
            runCatching { target.delete() }
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
                onLog("[失败] 返回内容过小($len B)，不是有效的 bootstrap 包")
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
            out.close(); out = null
            conn.disconnect(); conn = null

            if (abort) {
                onLog("[取消] 下载已中止")
                return false
            }
            // 长度已知时必须完整，防止静默截断
            if (len > 0 && target.length() != len) {
                onLog("[失败] 下载被截断：${target.length()}/$len 字节")
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

    /** 全量 CRC 校验，能抓出静默截断 */
    private fun verifyZip(file: File, onLog: (String) -> Unit): Boolean {
        if (!file.exists() || file.length() < 1024) {
            onLog("[校验] 文件不存在或过小")
            return false
        }
        return try {
            ZipFile(file).use { zf ->
                var count = 0
                val buf = ByteArray(64 * 1024)
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val crc = CRC32()
                    zf.getInputStream(e).use { inp ->
                        while (true) {
                            val n = inp.read(buf)
                            if (n <= 0) break
                            crc.update(buf, 0, n)
                        }
                    }
                    if (e.size > 0 && crc.value != e.crc) {
                        onLog("[校验] 条目损坏：${e.name}")
                        return false
                    }
                    count++
                }
                onLog("[校验] zip 完整，共 $count 个文件")
                count > 100
            }
        } catch (e: Exception) {
            onLog("[校验] 无法读取 zip：${e.message}")
            false
        }
    }

    // ---------------- 解压 ----------------

    /** 系统 unzip / toybox unzip 能还原符号链接，优先使用 */
    private fun trySystemUnzip(zip: File, dest: File, onLog: (String) -> Unit): Boolean {
        val cmds = listOf(
            listOf("/system/bin/unzip", "-q", "-o", zip.absolutePath, "-d", dest.absolutePath),
            listOf("/system/bin/toybox", "unzip", "-q", "-o", zip.absolutePath, "-d", dest.absolutePath),
            listOf("/system/xbin/unzip", "-q", "-o", zip.absolutePath, "-d", dest.absolutePath)
        )
        for (cmd in cmds) {
            if (!File(cmd[0]).exists()) continue
            try {
                val p = ProcessBuilder(cmd)
                    .directory(dest)
                    .redirectErrorStream(true)
                    .start()
                p.inputStream.bufferedReader().use { it.readText() }
                val code = p.waitFor()
                onLog("[解压] ${cmd[0]} 退出码 $code")
                if (code == 0 && hasUsrSomewhere(dest)) return true
            } catch (e: Exception) {
                onLog("[解压] ${cmd[0]} 不可用：${e.message}")
            }
        }
        return false
    }

    private fun hasUsrSomewhere(base: File): Boolean {
        if (File(base, "usr/bin/bash").exists() || File(base, "usr/bin/sh").exists()) return true
        return base.listFiles()?.any { it.isDirectory && File(it, "usr").exists() } == true
    }

    private fun unzipJava(zip: File, destDir: File, onLog: (String) -> Unit) {
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
        onLog("[解压] Java 解压写入 $count 个文件")
    }

    /** 把任意深度下的 usr/ 提升到 filesDir/usr */
    private fun normalizeLayout(onLog: (String) -> Unit) {
        val root = App.ctx.filesDir
        if (File(root, "usr/bin/bash").exists() || File(root, "usr/bin/sh").exists()) return
        val holder = findUsrHolder(root) ?: return
        onLog("[整理] 归一化：${holder.absolutePath}/usr -> ${root.absolutePath}/usr")
        holder.listFiles()?.forEach { f ->
            val dest = File(root, f.name)
            if (dest.exists()) dest.deleteRecursively()
            if (!f.renameTo(dest)) {
                runCatching { f.copyRecursively(dest, true) }
            }
        }
        runCatching { holder.deleteRecursively() }
    }

    /** BFS 找含 usr/bin/{bash,sh} 的目录，最多 5 层 */
    private fun findUsrHolder(base: File): File? {
        val q = ArrayDeque<Pair<File, Int>>()
        q.add(base to 0)
        while (q.isNotEmpty()) {
            val (dir, depth) = q.removeFirst()
            val usr = File(dir, "usr")
            if (usr.isDirectory &&
                (File(usr, "bin/bash").exists() || File(usr, "bin/sh").exists())
            ) return dir
            if (depth >= 5) continue
            dir.listFiles()
                ?.filter { it.isDirectory && it.name != "usr" }
                ?.forEach { q.add(it to depth + 1) }
        }
        return null
    }

    /** SYMLINKS.txt / EXECUTABLES.txt 可能在子目录里，统一提到 filesDir 根 */
    private fun locateMeta(name: String): File {
        val direct = File(App.ctx.filesDir, name)
        if (direct.exists()) return direct
        val q = ArrayDeque<Pair<File, Int>>()
        q.add(App.ctx.filesDir to 0)
        while (q.isNotEmpty()) {
            val (dir, depth) = q.removeFirst()
            val f = File(dir, name)
            if (f.exists()) return f
            if (depth >= 3) continue
            dir.listFiles()?.filter { it.isDirectory }?.forEach { q.add(it to depth + 1) }
        }
        return direct
    }

    private fun relocateMetaFiles(onLog: (String) -> Unit) {
        listOf("SYMLINKS.txt", "EXECUTABLES.txt").forEach { name ->
            val f = locateMeta(name)
            if (!f.exists()) return@forEach
            val dest = File(App.ctx.filesDir, name)
            if (f.absolutePath != dest.absolutePath) {
                runCatching {
                    Files.copy(f.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    /** symlink 被解成 0 字节占位文件，删掉后由 SYMLINKS.txt 重建 */
    private fun dropEmptyPlaceholders(onLog: (String) -> Unit) {
        var n = 0
        listOf("bin", "bin/applets", "libexec").forEach { rel ->
            val dir = File(Env.prefix, rel)
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.length() == 0L && f.name != ".nomedia") {
                    if (f.delete()) n++
                }
            }
        }
        if (n > 0) onLog("[清理] 移除 $n 个空占位文件（未还原的符号链接）")
    }

    /**
     * SYMLINKS.txt：新版格式 "目标←链接路径"（← 为 U+2190），兼容旧版 ":" 分隔。
     * 链接路径相对 PREFIX。
     */
    private fun applySymlinks(file: File, onLog: (String) -> Unit) {
        if (!file.exists()) {
            onLog("[警告] 未找到 SYMLINKS.txt")
            return
        }
        var count = 0
        val pending = mutableListOf<Pair<String, String>>()
        file.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val parts = when {
                line.contains("←") -> line.split("←", limit = 2)
                line.contains(":") -> line.split(":", limit = 2)
                else -> return@forEach
            }
            if (parts.size != 2) return@forEach
            // 两种顺序都试一遍，避免解析反了
            pending += (parts[0].trim() to parts[1].trim())
        }
        for ((a, b) in pending) {
            val isAbsFirst = a.startsWith("/")
            val targetRaw: String
            val linkRaw: String
            if (isAbsFirst || a.contains("/")) {
                targetRaw = a; linkRaw = b
            } else {
                targetRaw = b; linkRaw = a
            }
            val target = Paths.get(
                if (targetRaw.startsWith("/")) targetRaw
                else File(Env.prefix, targetRaw).absolutePath
            )
            val link = File(Env.prefix, linkRaw)
            try {
                link.parentFile?.mkdirs()
                if (link.exists() || Files.isSymbolicLink(link.toPath())) {
                    if (link.isFile && link.length() > 0) continue // 已是真实文件，别覆盖
                    link.delete()
                }
                Files.createSymbolicLink(link.toPath(), target)
                count++
            } catch (e: Exception) {
                // 个别链接失败不影响整体
            }
        }
        onLog("[链接] 建立符号链接 $count 个")
    }

    private fun applyExecutables(file: File, onLog: (String) -> Unit) {
        if (!file.exists()) return
        var count = 0
        file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { rel ->
            val f = File(Env.prefix, rel)
            if (f.exists() && !Files.isSymbolicLink(f.toPath())) {
                runCatching { f.setExecutable(true, true); f.setReadable(true, true) }
                count++
            }
        }
        onLog("[权限] 按 EXECUTABLES.txt 授权 $count 个文件")
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
            else root.walkTopDown()
                .filter { it.isFile && it.length() > 0 }
                .forEach { it.setExecutable(true, false) }
        }
        onLog("[权限] 已赋予可执行文件权限")
    }

    /** bash 缺失时，用 applets / busybox / sh 补出来 */
    private fun repairBash(onLog: (String) -> Unit): Boolean {
        val bash = File(Env.prefix, "bin/bash")
        if (bash.exists() && bash.length() > 0) {
            bash.setExecutable(true)
            return true
        }
        val candidates = listOf(
            "bin/applets/bash", "bin/bash.real", "bin/busybox",
            "bin/ash", "bin/sh", "bin/applets/sh"
        )
        for (rel in candidates) {
            val src = File(Env.prefix, rel)
            if (!src.exists() || src.length() == 0L) continue
            try {
                bash.parentFile?.mkdirs()
                if (bash.exists()) bash.delete()
                Files.copy(src.toPath(), bash.toPath(), StandardCopyOption.REPLACE_EXISTING)
                bash.setExecutable(true, false)
                onLog("[修复] 用 $rel 补出 bin/bash")
                return true
            } catch (e: Exception) {
                // 继续下一个候选
            }
        }
        // 退一步：让 shell 指向 sh
        val sh = File(Env.prefix, "bin/sh")
        if (sh.exists() && sh.length() > 0) {
            sh.setExecutable(true)
            onLog("[修复] 无 bash，改用 bin/sh 作为 shell")
            return true
        }
        return false
    }

    /** 失败时打印实际目录结构，方便定位 */
    private fun diagnose(): String {
        val root = App.ctx.filesDir
        val top = root.listFiles()?.map { if (it.isDirectory) "${it.name}/" else it.name }
            ?.sorted()?.take(20) ?: emptyList()
        val usr = File(Env.prefix, "bin")
        val bins = if (usr.isDirectory) {
            usr.listFiles()?.filter { it.isFile && it.length() > 0 }
                ?.map { it.name }?.sorted()?.take(15) ?: emptyList()
        } else emptyList()
        return "顶层=${top.joinToString(",")} | usr/bin 非空文件=${bins.joinToString(",")}"
    }
}

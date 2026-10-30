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
 * 解压要点：
 * 1. 新版 bootstrap 把符号链接作为真实 symlink entry 存进 zip，
 *    Java 的 ZipInputStream 会把它解成 0 字节空文件或一行路径的小文件；
 * 2. Android 应用数据分区无法创建符号链接（Os.symlink 直接抛异常），
 *    因此本实现**完全不使用 symlink**，一律把实体文件复制到位，功能等价；
 * 3. 优先调用系统 unzip，失败再回退 Java 解压 + materializeLinks 还原。
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
            // zip 本身是完整的（CRC 已过），结构问题换源也没用，别再烧流量
            onLog("[中止] zip 完整但解压后结构不可修复，停止重试")
            dumpZipEntries(zipFile, onLog)
            wipePrefix()
            zipFile.delete()
            onLog("[诊断] ${diagnose()}")
            onLog("[建议] 用「导入运行环境(离线)」选中电脑上下载好的 bootstrap-aarch64.zip")
            return@withContext false
        }

        onLog("[失败] 所有下载源均不可用。")
        onLog("[建议] 1) 主界面填可用的 bootstrap 直链；2) 或用「导入运行环境(离线)」选中电脑上下载好的 zip。")
        onLog("[诊断] ${diagnose()}")
        false
    }

    /** 离线导入：直接用用户选中的 zip */
    suspend fun importZip(src: File, onLog: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        onLog("[导入] 文件 ${src.name}，${src.length() / 1024} KB")
        if (!verifyZip(src, onLog)) {
            onLog("[失败] zip 校验不通过，文件可能已损坏")
            dumpZipEntries(src, onLog)
            return@withContext false
        }
        onLog("[导入] 清理旧运行环境…")
        wipePrefix()
        val ok = installFrom(src, onLog)
        if (!ok) {
            dumpZipEntries(src, onLog)
            onLog("[诊断] ${diagnose()}")
        }
        onLog(if (ok) "[完成] 运行环境导入成功" else "[失败] 导入未成功")
        ok
    }

    /** 下载 → 解压 → 归一化 → 还原链接 → 修权限 → 校验 shell */
    private fun installFrom(zip: File, onLog: (String) -> Unit): Boolean {
        val root = App.ctx.filesDir
        return try {
            root.mkdirs()
            if (!trySystemUnzip(zip, root, onLog)) {
                onLog("[解压] 系统 unzip 不可用，改用 Java 解压（符号链接需还原）")
                unzipJava(zip, root, onLog)
            }
            normalizeLayout(onLog)
            relocateMetaFiles(onLog)
            materializeLinks(onLog)          // 关键：把链接还原成真实文件
            applySymlinks(locateMeta("SYMLINKS.txt"), onLog)
            applyExecutables(locateMeta("EXECUTABLES.txt"), onLog)
            chmodExecutables(onLog)
            Env.prepareDirs()
            repairShell(onLog)
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

    /** shell 必须是真实可执行文件：0 字节或几十字节的“链接文本”都不算 */
    private fun ready(): Boolean {
        val s = Env.shell()
        return s.exists() && isRealBinary(s) && (s.canExecute() || s.setExecutable(true))
    }

    /** 真实 ELF / 脚本，或体积足够大的文件；排除 0 字节与 symlink 文本 */
    private fun isRealBinary(f: File): Boolean {
        if (!f.exists() || !f.isFile) return false
        val len = f.length()
        if (len == 0L) return false
        if (len < 4096 && !isElf(f)) return false
        return true
    }

    private fun isElf(f: File): Boolean = runCatching {
        f.inputStream().use {
            val b = ByteArray(4)
            val n = it.read(b)
            n == 4 && b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte() &&
                b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

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
            if (!File(cmd[0]).exists()) {
                onLog("[解压] 无 ${cmd[0]}")
                continue
            }
            try {
                val p = ProcessBuilder(cmd)
                    .directory(dest)
                    .redirectErrorStream(true)
                    .start()
                val out = p.inputStream.bufferedReader().use { it.readText() }
                val code = p.waitFor()
                val tail = out.lineSequence().lastOrNull { it.isNotBlank() }?.take(120) ?: ""
                onLog("[解压] ${cmd.joinToString(" ")} -> 退出码 $code ${if (tail.isNotEmpty()) "($tail)" else ""}")
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

    /**
     * 核心修复：把「符号链接」还原成真实文件。
     *
     * Java 解压（以及部分 unzip）会有两种产物：
     *   a) 0 字节空文件（链接信息丢失）
     *   b) 内容只有一行路径的小文件（链接目标被当成了文件内容）
     * 两者都不能执行。这里统一处理：
     *   1. 同名的 bin/applets/<name> 是真实二进制，直接复制覆盖；
     *   2. 内容是路径时，把路径重映射到本机 PREFIX 后复制真实文件；
     *   3. 都找不到就删除，避免留下不可执行的假文件。
     */
    private fun materializeLinks(onLog: (String) -> Unit) {
        val prefix = Env.prefix
        val applets = File(prefix, "bin/applets")
        var fixed = 0
        var dropped = 0

        val dirs = listOf(
            File(prefix, "bin"),
            applets,
            File(prefix, "libexec"),
            File(prefix, "lib/apt/apt-helper"),
            File(prefix, "lib/apt/methods")
        )

        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile || f.name == ".nomedia") continue
                if (isRealBinary(f)) continue

                // 1) 同名真实二进制优先（bin/applets/<name> 是 Termux 存放实体的地方）
                val byName = File(applets, f.name)
                if (byName.exists() && byName.absolutePath != f.absolutePath && isRealBinary(byName)) {
                    if (copyOver(byName, f)) { fixed++; continue } else { dropped++; continue }
                }

                // 2) 内容是链接目标路径（Termux 绝对路径需重映射到本机）
                val content = readLinkText(f)
                if (content != null) {
                    val target = resolveLinkTarget(content)
                    if (target != null && isRealBinary(target)) {
                        if (copyOver(target, f)) { fixed++; continue } else { dropped++; continue }
                    }
                    val leaf = content.substringAfterLast('/')
                    val alt = File(applets, leaf)
                    if (alt.exists() && isRealBinary(alt)) {
                        if (copyOver(alt, f)) { fixed++; continue } else { dropped++; continue }
                    }
                }

                // 3) 全树找同名实体兜底
                findRealBinary(f.name)?.let { src ->
                    if (src.absolutePath != f.absolutePath && copyOver(src, f)) {
                        fixed++; return@let
                    }
                }
                if (isRealBinary(f)) { fixed++; continue }

                // 4) 仍救不回来：只在确定是“坏链接”时删除，小脚本要保留
                val isBrokenLink = f.length() == 0L ||
                    (content != null && !isElf(f) && f.length() < 4096)
                if (isBrokenLink && f.delete()) dropped++
            }
        }
        onLog("[还原] 链接还原：修复 $fixed 个，移除 $dropped 个无效文件")
    }

    /** 读取疑似链接文本：单行、可打印、像路径 */
    private fun readLinkText(f: File): String? {
        if (f.length() > 4096) return null
        return runCatching {
            val s = f.readText(Charsets.UTF_8).trim()
            if (s.isEmpty() || s.contains('\n')) return null
            if (s.any { it.code < 0x20 }) return null
            if (!s.contains('/')) return null
            s
        }.getOrNull()
    }

    /** 把 Termux 的绝对路径重映射到本机 filesDir，相对路径按 PREFIX 解析 */
    private fun resolveLinkTarget(raw: String): File? {
        val root = App.ctx.filesDir.absolutePath
        val mapped = raw.replace(Regex("^/data/data/com\\.termux/files"), root)
        val f = if (mapped.startsWith("/")) File(mapped)
        else File(Env.prefix, mapped)
        val alt = File(Env.prefix, raw)
        return when {
            f.exists() -> f
            alt.exists() -> alt
            else -> null
        }
    }

    private fun copyOver(src: File, dst: File): Boolean = runCatching {
        if (dst.exists()) dst.delete()
        src.copyTo(dst, true)
        dst.setExecutable(true, false)
        dst.setReadable(true, false)
        true
    }.getOrDefault(false)

    /**
     * SYMLINKS.txt：新版格式 "目标←链接路径"（← 为 U+2190），兼容旧版 ":" 分隔。
     * 链接路径相对 PREFIX。
     */
    /**
     * SYMLINKS.txt：新版格式 "目标←链接路径"（← 为 U+2190），兼容旧版 ":" 分隔。
     * 两种顺序都试，取“目标真实存在”的那种；symlink 建不起来就复制实体。
     */
    private fun applySymlinks(file: File, onLog: (String) -> Unit) {
        if (!file.exists()) {
            onLog("[警告] 未找到 SYMLINKS.txt")
            return
        }
        var count = 0
        val pairs = mutableListOf<Pair<String, String>>()
        file.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val parts = when {
                line.contains("\u2190") -> line.split("\u2190", limit = 2)
                line.contains("\u2192") -> line.split("\u2192", limit = 2).let { listOf(it[1], it[0]) }
                line.contains(":") -> line.split(":", limit = 2)
                else -> return@forEach
            }
            if (parts.size != 2) return@forEach
            pairs += (parts[0].trim() to parts[1].trim())
        }

        for ((a, b) in pairs) {
            var done = false
            for ((targetRaw, linkRaw) in listOf(a to b, b to a)) {
                if (linkRaw.isBlank() || targetRaw.isBlank()) continue
                val target = resolveLinkTarget(targetRaw) ?: continue
                if (!isRealBinary(target)) continue
                val link = File(Env.prefix, linkRaw)
                if (link.absolutePath == target.absolutePath) { done = true; break }
                if (ensureLink(link, target)) { done = true; break }
            }
            if (done) count++
        }
        onLog("[链接] 建立链接 $count 个（symlink 失败时自动复制实体）")
    }

    /**
     * Android 无法创建符号链接（Os.symlink 在应用数据分区通常直接抛异常），
     * 因此一律复制实体文件——功能等价且永远可用。
     */
    private fun ensureLink(link: File, target: File): Boolean {
        if (isRealBinary(link)) return true
        if (link.absolutePath == target.absolutePath) return true
        return copyOver(target, link)
    }

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

    /** shell 仍不可用时，全树搜索真实二进制补出 bash / sh */
    private fun repairShell(onLog: (String) -> Unit): Boolean {
        val prefix = Env.prefix
        val bash = File(prefix, "bin/bash")
        if (isRealBinary(bash)) {
            bash.setExecutable(true)
            return true
        }
        for (name in listOf("bash", "bash.real")) {
            findRealBinary(name)?.let { src ->
                runCatching {
                    bash.parentFile?.mkdirs()
                    copyOver(src, bash)
                }
                if (isRealBinary(bash)) {
                    onLog("[修复] 用 ${src.absolutePath.removePrefix(prefix.absolutePath)} 补出 bin/bash")
                    return true
                }
            }
        }
        // bash 补不出，就降级用 sh / ash / busybox
        for (name in listOf("sh", "ash", "busybox")) {
            val f = File(prefix, "bin/$name")
            if (isRealBinary(f)) {
                f.setExecutable(true)
                onLog("[修复] 无 bash，改用 bin/$name 作为 shell")
                return true
            }
            findRealBinary(name)?.let { src ->
                runCatching { copyOver(src, f) }
                if (isRealBinary(f)) {
                    onLog("[修复] 无 bash，改用 ${src.name} 作为 shell")
                    return true
                }
            }
        }
        return false
    }

    /** BFS 全树找同名且真实可执行的二进制，取体积最大的 */
    private fun findRealBinary(name: String): File? {
        val q = ArrayDeque<Pair<File, Int>>()
        q.add(Env.prefix to 0)
        var best: File? = null
        while (q.isNotEmpty()) {
            val (dir, depth) = q.removeFirst()
            val f = File(dir, name)
            if (isRealBinary(f) && (best == null || f.length() > best!!.length())) best = f
            if (depth >= 4) continue
            dir.listFiles()
                ?.filter { it.isDirectory && it.name != "home" }
                ?.forEach { q.add(it to depth + 1) }
        }
        return best
    }

    /** 打印 zip 内部结构，一眼看清包到底长什么样 */
    private fun dumpZipEntries(zip: File, onLog: (String) -> Unit) {
        onLog("[结构] ---- zip 条目（前 40 个）----")
        runCatching {
            ZipFile(zip).use { zf ->
                val entries = zf.entries()
                var i = 0
                while (entries.hasMoreElements() && i < 40) {
                    val e = entries.nextElement()
                    onLog("[结构] ${if (e.isDirectory) "D" else "F"} ${e.size}\t${e.name}")
                    i++
                }
            }
        }
        onLog("[结构] -----------------------------")
    }

    /** 失败时打印实际目录结构，方便定位 */
    private fun diagnose(): String {
        val root = App.ctx.filesDir
        val top = root.listFiles()?.map { if (it.isDirectory) "${it.name}/" else it.name }
            ?.sorted()?.take(20) ?: emptyList()
        val bin = File(Env.prefix, "bin")
        val bins = if (bin.isDirectory) {
            bin.listFiles()?.filter { it.isFile }
                ?.map { "${it.name}(${it.length()})" }?.sorted()?.take(15) ?: emptyList()
        } else emptyList()
        val applets = File(Env.prefix, "bin/applets")
        val ap = if (applets.isDirectory) {
            applets.listFiles()?.filter { it.isFile }
                ?.map { "${it.name}(${it.length()})" }?.sorted()?.take(10) ?: emptyList()
        } else emptyList()
        return "顶层=${top.joinToString(",")} | usr/bin=${bins.joinToString(",")} | applets=${ap.joinToString(",")}"
    }
}

package com.yuanbao.gradlebuilder

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * 下载并解压 Termux 官方 bootstrap，在 App 私有目录中搭出一个真实的 Linux 用户态。
 *
 * 正确来源（注意不是 termux-app）：
 *   https://github.com/termux/termux-packages/releases/download/
 *     bootstrap-2026.10.25-r1%2Bapt.android-7/bootstrap-aarch64.zip
 * tag 里的 "+" 必须编码成 %2B，否则 404。
 *
 * 三个关键设计（都是被真机踩出来的坑）：
 * 1. Android 应用数据分区建不了符号链接，因此**全程不使用 symlink**，
 *    一律把实体文件复制到链接位置，功能等价；
 * 2. Java 解压会把 zip 里的 symlink entry 写成「内容是一行路径」的小文件，
 *    所以解压后要按多轮迭代把这些链接还原成真实文件；
 * 3. 用 ZipFile（中央目录）而不是 ZipInputStream 逐条读，更稳，且能拿到每个条目的真实大小。
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
                onLog("[重试] 压缩包损坏，换下一个源…")
                zipFile.delete()
                continue
            }
            onLog("[解压] 正在写入运行环境…")
            if (installFrom(zipFile, onLog)) {
                onLog("[完成] 基础运行环境就绪")
                return@withContext true
            }
            // zip 本身完整，结构问题换源也没用，别再烧流量
            onLog("[中止] zip 完整但解压后结构不可用，停止重试")
            zipFile.delete()
            dumpDiagnostics(onLog)
            onLog("[建议] 改用「导入运行环境(离线)」，或在主界面填可用的直链")
            return@withContext false
        }

        onLog("[失败] 所有下载源均不可用")
        onLog("[建议] 用「导入运行环境(离线)」选中电脑上下载好的 bootstrap-aarch64.zip")
        false
    }

    /** 离线导入：直接用用户选中的 zip */
    suspend fun importZip(src: File, onLog: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        onLog("[导入] 文件：${src.name}，${src.length() / 1024} KB")
        if (!verifyZip(src, onLog)) {
            onLog("[失败] zip 校验不通过，文件可能已损坏")
            return@withContext false
        }
        onLog("[导入] 清理旧运行环境…")
        wipePrefix()
        val ok = installFrom(src, onLog)
        if (!ok) dumpDiagnostics(onLog)
        onLog(if (ok) "[完成] 运行环境导入成功" else "[失败] 导入未成功")
        ok
    }

    /** 解压 → 还原链接 → 修权限 → 校验 shell */
    private fun installFrom(zip: File, onLog: (String) -> Unit): Boolean {
        val root = App.ctx.filesDir
        return try {
            root.mkdirs()
            Env.prepareDirs()
            val links = extract(zip, onLog)
            ensurePrefixLayout(onLog)
            resolveLinks(links, onLog)
            applySymlinksTxt(onLog)
            applyExecutablesTxt(onLog)
            chmodExecutables(onLog)
            repairShell(onLog)
            val ok = ready()
            onLog(
                if (ok) "[校验] shell = ${Env.shell().absolutePath} (${Env.shell().length()} bytes)"
                else "[失败] 未找到可用的 shell"
            )
            ok
        } catch (e: Exception) {
            onLog("[失败] ${e.message}")
            false
        }
    }

    // ---------------- 解压 + 链接识别 ----------------

    private class Link(val linkFile: File, val targetRaw: String)

    /**
     * 用 ZipFile（中央目录）逐条解压。
     * - 路径归一化：条目名里出现 "usr/" 时，把 usr 之前的所有层级（files/、data/data/com.termux/files/ 等）全部剥掉，
     *   统一落到 filesDir/usr；SYMLINKS.txt / EXECUTABLES.txt 落到 filesDir 根。
     * - 同时识别「疑似 symlink」条目：内容是一行路径的小文件，记录下来留给 resolveLinks 还原。
     */
    private fun extract(zip: File, onLog: (String) -> Unit): MutableList<Link> {
        val root = App.ctx.filesDir
        val links = mutableListOf<Link>()
        var real = 0
        var linkCount = 0
        var skipped = 0

        ZipFile(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                val raw = e.name
                val dest = mapPath(root, raw)
                if (dest == null) {
                    skipped++
                    continue
                }

                if (e.isDirectory) {
                    dest.mkdirs()
                    continue
                }
                dest.parentFile?.mkdirs()
                zf.getInputStream(e).use { inp ->
                    FileOutputStream(dest).use { out -> inp.copyTo(out) }
                }

                val txt = readLinkText(dest)
                if (txt != null) {
                    links += Link(dest, txt)
                    linkCount++
                } else {
                    real++
                }
            }
        }
        onLog("[解压] 真实文件 $real 个，疑似链接 $linkCount 个${if (skipped > 0) "，跳过 $skipped 个" else ""}")
        return links
    }

    /** 把 zip 内路径映射到本机：剥掉 usr/ 之前的层级；meta 文件放根目录 */
    private fun mapPath(root: File, raw: String): File? {
        val clean = raw.trim().trimStart('/')
        if (clean.isEmpty()) return null
        val base = clean.substringAfterLast('/')
        if (base == "SYMLINKS.txt" || base == "EXECUTABLES.txt") return File(root, base)

        val idx = clean.indexOf("usr/")
        if (idx >= 0) return File(root, clean.substring(idx))
        // 没有 usr/ 的条目（极少），原样放
        return File(root, clean)
    }

    /** 兜底：有些包顶层没有 usr/，直接是 bin/ lib/，把它们搬进 filesDir/usr */
    private fun ensurePrefixLayout(onLog: (String) -> Unit) {
        val root = App.ctx.filesDir
        if (File(Env.prefix, "bin").isDirectory) return
        val direct = listOf("bin", "lib", "libexec", "etc", "share", "var", "opt", "include", "tmp")
            .filter { File(root, it).isDirectory }
        if (direct.isEmpty()) return
        onLog("[整理] 包内无 usr/，把 ${direct.joinToString(",")} 搬进 usr/")
        Env.prefix.mkdirs()
        direct.forEach { name ->
            val src = File(root, name)
            val dst = File(Env.prefix, name)
            if (dst.exists()) dst.deleteRecursively()
            if (!src.renameTo(dst)) runCatching { src.copyRecursively(dst, true) }
        }
    }

    /** 疑似 symlink：小文件、单行、像路径、无控制字符 */
    private fun readLinkText(f: File): String? {
        if (!f.isFile) return null
        val len = f.length()
        if (len == 0L) return ""      // 0 字节也算坏链接，target 留空走兜底
        if (len > 8192) return null
        return runCatching {
            val bytes = ByteArray(len.toInt())
            f.inputStream().use {
                var off = 0
                while (off < bytes.size) {
                    val n = it.read(bytes, off, bytes.size - off)
                    if (n <= 0) break
                    off += n
                }
            }
            // 二进制文件（ELF 等）直接排除
            for (b in bytes) if (b == 0.toByte()) return null
            val s = String(bytes, Charsets.UTF_8).trim()
            if (s.isEmpty()) return ""
            if (s.contains('\n')) return null
            if (!s.contains('/')) return null
            s
        }.getOrNull()
    }

    // ---------------- 链接还原（不使用 symlink，全部复制实体） ----------------

    /**
     * 多轮迭代：A→B→C 这样的链，要等 B、C 先还原成功才能修 A。
     * 任一条目解析不出目标时**保留原文件不删除**，绝不自毁数据。
     */
    private fun resolveLinks(links: MutableList<Link>, onLog: (String) -> Unit) {
        val pending = links.toMutableList()
        var fixed = 0

        repeat(4) { pass ->
            if (pending.isEmpty()) return@repeat
            val still = mutableListOf<Link>()
            for (lk in pending) {
                val target = resolveTarget(lk)
                if (target != null && target.absolutePath != lk.linkFile.absolutePath) {
                    if (copyOver(target, lk.linkFile)) {
                        fixed++
                        continue
                    }
                }
                still += lk
            }
            pending.clear()
            pending.addAll(still)
        }
        val unresolved = pending.size
        onLog("[还原] 链接还原 $fixed 个${if (unresolved > 0) "，仍有 $unresolved 个无法解析（已保留原文件）" else ""}")
    }

    /** 依次尝试：绝对路径映射 / 相对链接所在目录 / 相对 PREFIX / 相对 filesDir / 同名实体兜底 */
    private fun resolveTarget(lk: Link): File? {
        val raw = lk.targetRaw
        if (raw.isBlank()) return findRealBinary(lk.linkFile.name)

        val root = App.ctx.filesDir.absolutePath
        val mapped = raw.replace(Regex("^/data/data/com\\.termux/files"), root)

        val cands = mutableListOf<File>()
        if (mapped.startsWith("/")) cands += File(mapped)
        cands += File(lk.linkFile.parentFile ?: Env.prefix, mapped)
        cands += File(Env.prefix, mapped)
        cands += File(root, mapped.removePrefix("/"))
        cands += File(Env.prefix, "bin/applets/" + mapped.substringAfterLast('/'))
        cands += File(Env.prefix, "bin/" + mapped.substringAfterLast('/'))

        cands.firstOrNull { it.isFile && it.exists() && isRealBinary(it) }?.let { return it }
        return findRealBinary(mapped.substringAfterLast('/'))
    }

    /** SYMLINKS.txt：格式 "目标←链接路径"（← 为 U+2190），兼容 → 与 : ；两种顺序都试 */
    private fun applySymlinksTxt(onLog: (String) -> Unit) {
        val f = File(App.ctx.filesDir, "SYMLINKS.txt")
        if (!f.exists()) {
            onLog("[链接] 无 SYMLINKS.txt（zip 内链接信息已单独处理）")
            return
        }
        val pairs = mutableListOf<Pair<String, String>>()
        f.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val parts = when {
                line.contains("←") -> line.split("←", limit = 2)
                line.contains("→") -> line.split("→", limit = 2).let { listOf(it[1], it[0]) }
                line.contains(":") -> line.split(":", limit = 2)
                else -> return@forEach
            }
            if (parts.size != 2) return@forEach
            pairs += (parts[0].trim() to parts[1].trim())
        }

        val links = pairs.flatMap { (a, b) ->
            listOf(
                Link(File(Env.prefix, b), a),
                Link(File(Env.prefix, a), b)
            )
        }.toMutableList()

        var done = 0
        repeat(3) {
            if (links.isEmpty()) return@repeat
            val still = mutableListOf<Link>()
            for (lk in links) {
                val target = resolveTarget(lk)
                if (target != null && target.absolutePath != lk.linkFile.absolutePath &&
                    copyOver(target, lk.linkFile)
                ) {
                    done++
                } else if (target != null && target.absolutePath == lk.linkFile.absolutePath) {
                    done++
                } else {
                    still += lk
                }
            }
            links.clear()
            links.addAll(still)
        }
        onLog("[链接] 按 SYMLINKS.txt 补齐 $done/${pairs.size} 条")
    }

    private fun applyExecutablesTxt(onLog: (String) -> Unit) {
        val f = File(App.ctx.filesDir, "EXECUTABLES.txt")
        if (!f.exists()) return
        var count = 0
        f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { rel ->
            val t = File(Env.prefix, rel)
            if (t.exists()) {
                runCatching { t.setExecutable(true, true); t.setReadable(true, true) }
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
        var n = 0
        roots.filter { it.exists() }.forEach { root ->
            if (root.isFile) {
                root.setExecutable(true); n++
            } else root.walkTopDown().filter { it.isFile }.forEach {
                it.setExecutable(true, false)
                it.setReadable(true, false)
                n++
            }
        }
        // 兜底：所有 ELF 都给执行权限
        File(Env.prefix, "bin").takeIf { it.isDirectory }?.walkTopDown()
            ?.filter { it.isFile && it.length() > 4096 && isElf(it) }
            ?.forEach { it.setExecutable(true, false); n++ }
        onLog("[权限] 已授权 $n 个文件")
    }

    /** shell 兜底链：bash → bash.real → sh → ash → busybox → 任意可用 ELF */
    private fun repairShell(onLog: (String) -> Unit): Boolean {
        val prefix = Env.prefix
        val bash = File(prefix, "bin/bash")
        if (isRealBinary(bash)) {
            bash.setExecutable(true)
            return true
        }
        for (rel in listOf("bin/bash.real", "bin/applets/bash", "bin/bash-5.2", "bin/bash5")) {
            val f = File(prefix, rel)
            if (isRealBinary(f)) {
                copyOver(f, bash)
                if (isRealBinary(bash)) {
                    onLog("[修复] 用 $rel 补出 bin/bash")
                    return true
                }
            }
        }
        findRealBinary("bash")?.let { src ->
            copyOver(src, bash)
            if (isRealBinary(bash)) {
                onLog("[修复] 从 ${src.name} 补出 bin/bash")
                return true
            }
        }
        for (name in listOf("sh", "ash", "busybox", "mksh", "toybox")) {
            val f = File(prefix, "bin/$name")
            if (isRealBinary(f)) {
                f.setExecutable(true)
                onLog("[修复] 无 bash，改用 bin/$name 作为 shell")
                return true
            }
            findRealBinary(name)?.let { src ->
                copyOver(src, f)
                if (isRealBinary(f)) {
                    onLog("[修复] 无 bash，改用 ${src.name} 作为 shell")
                    return true
                }
            }
        }
        return lastResortShell(onLog)
    }

    /** 最后手段：全 filesDir 扫任何叫 bash/sh/ash/busybox 的大体积 ELF */
    private fun lastResortShell(onLog: (String) -> Unit): Boolean {
        var best: File? = null
        App.ctx.filesDir.walkTopDown()
            .filter { it.isFile && it.name in setOf("bash", "sh", "ash", "busybox", "mksh") }
            .filter { it.length() > 100_000 && isElf(it) }
            .forEach { if (best == null || it.length() > best!!.length()) best = it }
        val src = best ?: return false
        val dst = File(Env.prefix, "bin/${src.name}")
        dst.parentFile?.mkdirs()
        if (!copyOver(src, dst)) return false
        onLog("[修复] 全盘扫描兜底：用 ${src.absolutePath} 作为 shell")
        return isRealBinary(dst)
    }

    private fun ready(): Boolean {
        val s = Env.shell()
        return s.exists() && isRealBinary(s) && (s.canExecute() || s.setExecutable(true))
    }

    private fun isRealBinary(f: File): Boolean {
        if (!f.exists() || !f.isFile) return false
        val len = f.length()
        if (len == 0L) return false
        return len >= 4096 || isElf(f)
    }

    private fun isElf(f: File): Boolean = runCatching {
        f.inputStream().use {
            val b = ByteArray(4)
            val n = it.read(b)
            n == 4 && b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte() &&
                b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    /** BFS 全树找同名真实二进制，取体积最大的 */
    private fun findRealBinary(name: String): File? {
        if (name.isBlank()) return null
        val q = ArrayDeque<Pair<File, Int>>()
        q.add(Env.prefix to 0)
        var best: File? = null
        while (q.isNotEmpty()) {
            val (dir, depth) = q.removeFirst()
            val f = File(dir, name)
            if (isRealBinary(f) && (best == null || f.length() > best!!.length())) best = f
            if (depth >= 4) continue
            dir.listFiles()
                ?.filter { it.isDirectory && it.name != "home" && it.name != "tmp" }
                ?.forEach { q.add(it to depth + 1) }
        }
        return best
    }

    private fun copyOver(src: File, dst: File): Boolean = runCatching {
        if (dst.exists()) dst.delete()
        src.copyTo(dst, true)
        dst.setExecutable(true, false)
        dst.setReadable(true, false)
        true
    }.getOrDefault(false)

    private fun wipePrefix() {
        runCatching { Env.prefix.deleteRecursively() }
        runCatching { File(App.ctx.filesDir, "home").deleteRecursively() }
        listOf("SYMLINKS.txt", "EXECUTABLES.txt").forEach {
            runCatching { File(App.ctx.filesDir, it).delete() }
        }
    }

    // ---------------- 下载与校验 ----------------

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

    /** 全量 CRC 校验，抓出静默截断 */
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

    // ---------------- 诊断 ----------------

    /** 失败时把关键目录的真实情况打出来，方便一眼定位 */
    private fun dumpDiagnostics(onLog: (String) -> Unit) {
        onLog("[诊断] ---- 开始 ----")
        val root = App.ctx.filesDir
        val top = root.listFiles()?.map { if (it.isDirectory) "${it.name}/" else it.name }
            ?.sorted()?.take(20) ?: emptyList()
        onLog("[诊断] filesDir 顶层：${top.joinToString(", ")}")

        val bin = File(Env.prefix, "bin")
        if (!bin.isDirectory) {
            onLog("[诊断] usr/bin 不存在！usr 下：${Env.prefix.list()?.take(20)?.joinToString(",")}")
        } else {
            val all = bin.listFiles()?.filter { it.isFile } ?: emptyArray<File>().toList()
            val real = all.count { isRealBinary(it) }
            onLog("[诊断] usr/bin 共 ${all.size} 个文件，其中真实可执行 $real 个")
            all.sortedByDescending { it.length() }.take(12).forEach {
                onLog("[诊断]   ${it.name} = ${it.length()} B${if (isRealBinary(it)) " [OK]" else " [坏]"}")
            }
        }
        val applets = File(Env.prefix, "bin/applets")
        if (applets.isDirectory) {
            val ap = applets.listFiles()?.filter { it.isFile } ?: emptyList()
            onLog("[诊断] bin/applets 共 ${ap.size} 个，真实 ${ap.count { isRealBinary(it) }} 个")
        } else {
            onLog("[诊断] 无 bin/applets 目录")
        }
        onLog("[诊断] ---- 结束 ----")
    }
}

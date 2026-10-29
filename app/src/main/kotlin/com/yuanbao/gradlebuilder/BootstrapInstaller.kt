package com.yuanbao.gradlebuilder

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 下载并解压 Termux 官方 bootstrap，在 App 私有目录中搭出一个真实的 Linux 用户态。
 * 有了它才能真实运行 apt / java / gradle。
 */
object BootstrapInstaller {

    private const val BOOTSTRAP_VERSION = "v0.118.3"
    private const val BASE =
        "https://github.com/termux/termux-app/releases/download/$BOOTSTRAP_VERSION"

    fun abiName(): String {
        val abis = Build.SUPPORTED_ABIS
        for (abi in abis) {
            when (abi) {
                "arm64-v8a" -> return "aarch64"
                "armeabi-v7a", "armeabi" -> return "arm"
                "x86_64" -> return "x86_64"
                "x86" -> return "i686"
            }
        }
        return "aarch64"
    }

    fun url(): String = "$BASE/bootstrap-${abiName()}.zip"

    suspend fun ensureInstalled(onLog: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (Env.hasBootstrap()) {
            onLog("[环境] 基础运行环境已存在，跳过 bootstrap 下载")
            Env.prepareDirs()
            return@withContext true
        }

        val zipFile = File(App.ctx.cacheDir, "bootstrap-${abiName()}.zip")
        if (!zipFile.exists() || zipFile.length() < 1024) {
            onLog("[下载] ${url()}")
            if (!download(zipFile, onLog)) return@withContext false
        } else {
            onLog("[缓存] 复用已下载的 bootstrap：${zipFile.name}")
        }

        onLog("[解压] 正在写入运行环境…")
        return@withContext try {
            unzip(zipFile, App.ctx.filesDir, onLog)
            applySymlinks(File(App.ctx.filesDir, "SYMLINKS.txt"), onLog)
            chmodExecutables(onLog)
            Env.prepareDirs()
            Env.bash.setExecutable(true)
            onLog("[完成] 基础运行环境就绪")
            true
        } catch (e: Exception) {
            onLog("[失败] ${e.message}")
            false
        }
    }

    private fun download(target: File, onLog: (String) -> Unit): Boolean {
        var conn: HttpURLConnection? = null
        var out: FileOutputStream? = null
        return try {
            conn = URL(url()).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.connect()
            if (conn.responseCode !in 200..299) {
                onLog("[失败] HTTP ${conn.responseCode}")
                return false
            }
            val total = conn.contentLength.toLong()
            out = FileOutputStream(target)
            val buf = ByteArray(64 * 1024)
            var done = 0L
            var lastPct = -1
            while (true) {
                val n = conn.inputStream.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                done += n
                if (total > 0) {
                    val pct = (done * 100 / total).toInt()
                    if (pct / 10 != lastPct / 10) {
                        lastPct = pct
                        onLog("[下载] $pct% (${done / 1024} KB)")
                    }
                }
            }
            onLog("[下载] 完成，共 ${done / 1024} KB")
            true
        } catch (e: Exception) {
            onLog("[失败] 下载出错：${e.message}")
            false
        } finally {
            runCatching { out?.close() }
            runCatching { conn?.disconnect() }
        }
    }

    private fun unzip(zip: File, destDir: File, onLog: (String) -> Unit) {
        val destPath = destDir.canonicalPath
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
                }
                zis.closeEntry()
            }
        }
    }

    /** Termux bootstrap 用 SYMLINKS.txt 描述符号链接，格式为 "链接路径:目标" */
    private fun applySymlinks(file: File, onLog: (String) -> Unit) {
        if (!file.exists()) return
        var count = 0
        file.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(":")
            if (parts.size != 2) return@forEach
            val link = File(App.ctx.filesDir, parts[0])
            val target = parts[1]
            try {
                link.parentFile?.mkdirs()
                if (link.exists()) link.delete()
                java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target))
                count++
            } catch (e: Exception) {
                // 部分设备不支持 symlink，忽略
            }
        }
        onLog("[链接] 处理符号链接 $count 个")
    }

    private fun chmodExecutables(onLog: (String) -> Unit) {
        val roots = listOf(
            File(Env.prefix, "bin"),
            File(Env.prefix, "libexec"),
            File(Env.prefix, "bin/applets")
        )
        roots.filter { it.exists() }.forEach { root ->
            root.walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true, false) }
        }
        onLog("[权限] 已赋予可执行文件权限")
    }
}

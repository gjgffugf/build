package com.yuanbao.gradlebuilder

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 把用户通过系统文件夹授权选中的源码（SAF content:// 树）同步到 App 内部的真实路径，
 * 这样 Gradle 才能以真实文件系统方式读取并写入 build 目录。
 */
object WorkspaceSync {

    fun sync(ctx: Context, treeUri: Uri): Boolean {
        return try {
            val root = DocumentFile.fromTreeUri(ctx, treeUri)
                ?: DocumentFile.fromSingleUri(ctx, treeUri) ?: return false
            val dest = Env.project
            dest.mkdirs()
            val copied = copyDir(ctx, root, dest, 0)
            ExecEngine.log("[同步] 已同步 $copied 个文件到 ${dest.absolutePath}")
            copied > 0
        } catch (e: Exception) {
            ExecEngine.log("[同步失败] ${e.message}")
            false
        }
    }

    private fun copyDir(ctx: Context, src: DocumentFile, destDir: File, depth: Int): Int {
        if (depth > 20) return 0
        var count = 0
        val files = src.listFiles()
        for (f in files) {
            val name = f.name ?: continue
            if (name == "build" || name == ".gradle" || name == ".git") continue
            val out = File(destDir, name)
            when {
                f.isDirectory -> {
                    if (!out.exists()) out.mkdirs()
                    count += copyDir(ctx, f, out, depth + 1)
                }
                f.isFile -> {
                    try {
                        ctx.contentResolver.openInputStream(f.uri)?.use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                        count++
                    } catch (e: Exception) {
                        ExecEngine.log("[跳过] $name : ${e.message}")
                    }
                }
            }
        }
        return count
    }

    fun isGradleProject(): Boolean {
        val p = Env.project
        return File(p, "settings.gradle").exists() ||
            File(p, "settings.gradle.kts").exists() ||
            File(p, "build.gradle").exists() ||
            File(p, "build.gradle.kts").exists() ||
            File(p, "gradlew").exists()
    }

    fun isAndroidProject(): Boolean {
        val p = Env.project
        val candidates = listOf("build.gradle", "build.gradle.kts", "app/build.gradle", "app/build.gradle.kts")
        return candidates.map { File(p, it) }.filter { it.exists() }.any {
            it.readText().contains("com.android")
        }
    }

    fun projectName(): String {
        val p = Env.project
        val root = File(p, "settings.gradle").let { if (it.exists()) it else File(p, "settings.gradle.kts") }
        if (root.exists()) {
            val text = root.readText()
            Regex("rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']").find(text)
                ?.groupValues?.getOrNull(1)?.let { return it }
        }
        return p.name
    }

    /** 构建成功后把 APK / AAB 复制回用户授权目录 */
    fun exportOutputs(ctx: Context, treeUri: Uri): List<String> {
        val outputs = mutableListOf<File>()
        val root = Env.project
        if (root.exists()) {
            root.walkTopDown()
                .filter { it.isFile }
                .filter { it.name.endsWith(".apk") || it.name.endsWith(".aab") }
                .filter { it.absolutePath.contains("/build/outputs/") }
                .forEach { outputs.add(it) }
        }
        if (outputs.isEmpty()) return emptyList()

        val tree = DocumentFile.fromTreeUri(ctx, treeUri) ?: return emptyList()
        val dir = tree.findFile("builder-output") ?: tree.createDirectory("builder-output")
        ?: return emptyList()

        val done = mutableListOf<String>()
        for (f in outputs) {
            val mime = if (f.name.endsWith(".apk")) "application/vnd.android.package-archive"
            else "application/octet-stream"
            val target = dir.findFile(f.name) ?: dir.createFile(mime, f.name) ?: continue
            try {
                ctx.contentResolver.openOutputStream(target.uri)?.use { os ->
                    f.inputStream().use { it.copyTo(os) }
                }
                done.add(f.name)
            } catch (e: Exception) {
                ExecEngine.log("[导出失败] ${f.name} : ${e.message}")
            }
        }
        if (done.isNotEmpty()) {
            ExecEngine.log("[导出] 已输出到：${DocumentsContract.getTreeDocumentId(treeUri)}/builder-output")
        }
        return done
    }
}

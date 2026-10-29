package com.yuanbao.gradlebuilder

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: com.yuanbao.gradlebuilder.databinding.ActivityMainBinding

    /** 系统文件夹授权：选中目录后持久化权限 */
    private val pickDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            Prefs.treeUri = uri.toString()
            refresh()
            toast("已授权，正在同步源码…")
            autoBuild(uri)
        }

    /** 离线导入 bootstrap zip */
    private val importZipLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val tmp = withContext(Dispatchers.IO) {
                    val f = File(cacheDir, "import-bootstrap.zip")
                    contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { input.copyTo(it) }
                    }
                    f
                }
                val ok = withContext(Dispatchers.IO) {
                    BootstrapInstaller.importZip(tmp) { ExecEngine.log(it) }
                }
                toast(if (ok) "运行环境导入成功" else "导入失败，请查看终端输出")
                if (ok) Prefs.envReady = false
                refresh()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = com.yuanbao.gradlebuilder.databinding.ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnPickDir.setOnClickListener { pickDirLauncher.launch(null) }

        b.btnSetup.setOnClickListener {
            askNotificationPermission()
            startActivity(TerminalActivity.newIntent(this, TerminalActivity.MODE_SETUP))
        }

        b.btnBuild.setOnClickListener {
            askNotificationPermission()
            val uri = Prefs.treeUri?.let { Uri.parse(it) }
            if (uri == null) {
                toast(getString(R.string.need_workdir))
                return@setOnClickListener
            }
            if (!WorkspaceSync.isGradleProject() && Prefs.repo.isNullOrBlank()) {
                toast("工作目录里没找到 Gradle 项目，请重新选择或填写仓库地址")
                return@setOnClickListener
            }
            startActivity(TerminalActivity.newIntent(this, TerminalActivity.MODE_BUILD))
        }

        b.btnImportEnv.setOnClickListener {
            importZipLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }

        b.btnSave.setOnClickListener {
            Prefs.command = b.etCommand.text?.toString()?.trim().orEmpty().ifEmpty { null }
            Prefs.repo = b.etRepo.text?.toString()?.trim().orEmpty().ifEmpty { null }
            Prefs.sdk = b.etSdk.text?.toString()?.trim().orEmpty().ifEmpty { null }
            Prefs.bootstrapUrl = b.etBootstrap.text?.toString()?.trim().orEmpty().ifEmpty { null }
            toast("已保存")
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        b.tvWorkdir.text = Prefs.treeUri?.let { raw ->
            val uri = Uri.parse(raw)
            val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?: uri.lastPathSegment
            "工作目录：${Uri.decode(id ?: "未知")}"
        } ?: getString(R.string.workdir_none)
        b.tvEnv.text = if (Prefs.envReady && Env.isInstalled())
            getString(R.string.env_ready) else getString(R.string.env_not_ready)
        if (b.etCommand.text.isNullOrEmpty()) {
            b.etCommand.setText(Prefs.command ?: defaultCommand())
        }
        if (b.etRepo.text.isNullOrEmpty()) b.etRepo.setText(Prefs.repo ?: "")
        if (b.etSdk.text.isNullOrEmpty()) b.etSdk.setText(Prefs.sdk ?: "")
        if (b.etBootstrap.text.isNullOrEmpty()) b.etBootstrap.setText(Prefs.bootstrapUrl ?: "")
    }

    private fun defaultCommand(): String {
        val useWrapper = java.io.File(Env.project, "gradlew").exists()
        val task = if (WorkspaceSync.isAndroidProject()) "assembleDebug" else "build"
        return if (useWrapper) "./gradlew $task --no-daemon --console=plain --stacktrace"
        else "gradle $task --no-daemon --console=plain --stacktrace"
    }

    /** 授权后自动使用文件夹中的源码进行构建 */
    private fun autoBuild(uri: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { WorkspaceSync.sync(this@MainActivity, uri) }
            if (!ok) {
                toast("源码同步失败，请重新选择目录")
                return@launch
            }
            withContext(Dispatchers.IO) { Prefs.projectName = WorkspaceSync.projectName() }
            startActivity(TerminalActivity.newIntent(this@MainActivity, TerminalActivity.MODE_BUILD))
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

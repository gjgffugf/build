package com.yuanbao.gradlebuilder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yuanbao.gradlebuilder.databinding.ActivityTerminalBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TerminalActivity : AppCompatActivity() {

    private lateinit var b: ActivityTerminalBinding
    private var job: Job? = null
    private var flushJob: Job? = null
    private var mode = MODE_SETUP
    private var zipPath: String? = null
    private val pending = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(b.root)
        mode = intent.getIntExtra(EXTRA_MODE, MODE_SETUP)
        zipPath = intent.getStringExtra(EXTRA_ZIP)

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { requestExit() }
        b.toolbar.title = when (mode) {
            MODE_SETUP -> "部署环境"
            MODE_IMPORT -> "导入运行环境"
            else -> "构建应用"
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = requestExit()
        })

        b.btnBackHome.setOnClickListener { requestExit() }

        // 运行期间保活
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, ExecService::class.java))
        }

        startFlow()
        startFlushLoop()
    }

    // ---------- 流程 ----------

    private fun startFlow() {
        ExecEngine.clearLog()
        b.tvLog.text = ""
        b.tvResult.visibility = View.GONE
        setRunning(true)
        job = lifecycleScope.launch {
            val exit = runFlow()
            setRunning(false)
            onFinish(exit)
        }
    }

    private suspend fun runFlow(): Int {
        Env.prepareDirs()
        ExecEngine.log("[环境] 架构：${BootstrapInstaller.abiName()}，PREFIX=${Env.prefix}")

        if (mode == MODE_IMPORT) {
            val path = zipPath
            if (path.isNullOrBlank()) {
                ExecEngine.log("[错误] 未指定 zip 路径")
                return -1
            }
            val f = File(path)
            if (!f.exists() || f.length() < 1024) {
                ExecEngine.log("[错误] zip 不存在或过小：${f.absolutePath}")
                return -1
            }
            ExecEngine.log("[导入] ${f.absolutePath} (${f.length() / 1024} KB)")
            if (!BootstrapInstaller.importZip(f) { ExecEngine.log(it) }) return -1
            ExecEngine.log("[导入] 运行环境导入成功")
            if (Env.isInstalled()) {
                ExecEngine.log("[环境] Gradle 与 JDK 已就绪")
                return 0
            }
            return runSetup()
        }

        if (!BootstrapInstaller.ensureInstalled { ExecEngine.log(it) }) return -1
        if (!Env.isInstalled()) {
            val code = runSetup()
            if (code != 0) return code
            if (mode == MODE_SETUP) return 0
        } else {
            ExecEngine.log("[环境] Gradle 与 JDK 已就绪，跳过安装")
            if (mode == MODE_SETUP) return 0
        }
        return runBuild()
    }

    private suspend fun runSetup(): Int {
        ExecEngine.log("[环境] 开始安装 Gradle 与 JDK…")
        Env.secondStageScript()?.let {
            ExecEngine.log("[环境] 执行 bootstrap 第二阶段初始化…")
            ExecEngine.run("bash ${it.absolutePath}")
        }
        for (cmd in setupCommands()) {
            val code = ExecEngine.run(cmd)
            if (code != 0) {
                ExecEngine.log("[环境] 命令失败（退出码 $code）：$cmd")
                return code
            }
        }
        Prefs.envReady = true
        ExecEngine.log("[环境] 安装完成")
        if (!Env.isInstalled()) {
            ExecEngine.log("[警告] 未探测到 gradle/java：gradle=${Env.gradleBin()}, java=${Env.javaBin()}")
        }
        return 0
    }

    private fun setupCommands(): List<String> = listOf(
        "dpkg --configure -a",
        "apt update -y || apt-get update -y",
        "apt install -y --no-install-recommends procps git || apt-get install -y procps git",
        "apt install -y --no-install-recommends openjdk-17 gradle || apt-get install -y openjdk-17 gradle",
        "java -version",
        "gradle -v"
    )

    private suspend fun runBuild(): Int {
        val repo = Prefs.repo
        if (!repo.isNullOrBlank()) {
            ExecEngine.log("[源码] 从 $repo 克隆…")
            Env.workspace.mkdirs()
            val code = ExecEngine.run(
                "rm -rf project && git clone --depth 1 $repo project",
                Env.workspace
            )
            if (code != 0) return code
        }
        val projectDir = Env.project
        if (!projectDir.exists() || !WorkspaceSync.isGradleProject()) {
            ExecEngine.log("[错误] 未找到 Gradle 项目，请先选择工作目录或填写仓库地址")
            return -1
        }
        ExecEngine.run("chmod +x gradlew", projectDir)

        if (WorkspaceSync.isAndroidProject()) {
            val sdk = Env.sdkHome()
            if (!File(sdk).exists()) {
                ExecEngine.log("[警告] 未找到 Android SDK：$sdk，Android 项目可能无法完成打包")
            } else {
                ExecEngine.log("[SDK] $sdk")
            }
        }

        val cmd = Prefs.command?.takeIf { it.isNotBlank() } ?: defaultCommand()
        ExecEngine.log("[构建] 项目：${WorkspaceSync.projectName()}")
        return ExecEngine.run(cmd, projectDir)
    }

    private fun defaultCommand(): String {
        val useWrapper = File(Env.project, "gradlew").exists()
        val task = if (WorkspaceSync.isAndroidProject()) "assembleDebug" else "build"
        return if (useWrapper) "./gradlew $task --no-daemon --console=plain --stacktrace"
        else "gradle $task --no-daemon --console=plain --stacktrace"
    }

    /** 结果只写进日志 + 顶部横幅，绝不弹窗阻塞 */
    private suspend fun onFinish(exit: Int) {
        val exported = if (exit == 0 && mode == MODE_BUILD) {
            withContext(Dispatchers.IO) {
                Prefs.treeUri?.let { WorkspaceSync.exportOutputs(this@TerminalActivity, Uri.parse(it)) }
                    ?: emptyList()
            }
        } else emptyList()

        val title: String
        val msg: String
        when {
            mode == MODE_IMPORT && exit == 0 -> {
                title = getString(R.string.setup_done_title)
                msg = "运行环境导入成功，可以开始构建应用了。"
            }
            mode == MODE_SETUP && exit == 0 -> {
                title = getString(R.string.setup_done_title)
                msg = getString(R.string.setup_done_msg)
            }
            mode == MODE_SETUP -> {
                title = getString(R.string.setup_fail_title)
                msg = getString(R.string.setup_fail_msg)
            }
            exit == 0 -> {
                title = getString(R.string.build_ok_title)
                msg = if (exported.isEmpty())
                    "构建成功。若项目不是 Android 工程，产物通常在 build/libs 下。"
                else
                    "构建成功！产物：${exported.joinToString(", ")}（已导出到工作目录下的 builder-output/）"
            }
            else -> {
                title = getString(R.string.build_fail_title)
                msg = getString(R.string.build_fail_msg)
            }
        }

        ExecEngine.log("")
        ExecEngine.log("========== $title ==========")
        ExecEngine.log(msg)
        ExecEngine.log("====================================")
        saveLog()

        if (isFinishing || isDestroyed) return
        runOnUiThread {
            b.tvResult.text = "$title：$msg"
            b.tvResult.visibility = View.VISIBLE
            b.tvStatus.text = if (exit == 0) "完成(0)" else "失败($exit)"
        }
    }

    private fun saveLog() = runCatching {
        File(filesDir, "last-log.txt").writeText(ExecEngine.snapshot().joinToString("\n"))
    }

    // ---------- 返回拦截 ----------

    private fun requestExit() {
        if (ExecEngine.isRunning()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.stop_title)
                .setMessage(R.string.stop_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    BootstrapInstaller.abort = true
                    ExecEngine.stop()
                    job?.cancel()
                    doExit()
                }
                .show()
        } else {
            doExit()
        }
    }

    private fun doExit() {
        saveLog()
        runCatching { stopService(Intent(this, ExecService::class.java)) }
        finish()
    }

    // ---------- UI ----------

    private fun setRunning(running: Boolean) {
        b.progress.visibility = if (running) View.VISIBLE else View.GONE
        b.tvStatus.text = if (running) getString(R.string.running) else getString(R.string.idle)
    }

    private fun startFlushLoop() {
        flushJob?.cancel()
        flushJob = lifecycleScope.launch {
            // 先补齐历史，再持续追新
            b.tvLog.text = ExecEngine.snapshot().joinToString("\n")
            while (isActive) {
                delay(120)
                if (pending.isNotEmpty()) {
                    val text = pending.toString()
                    pending.setLength(0)
                    b.tvLog.append(text)
                    trim()
                    b.scroller.post { b.scroller.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
        lifecycleScope.launch { ExecEngine.live.collect { pending.append(it).append('\n') } }
    }

    private fun trim() {
        val tv = b.tvLog
        if (tv.length() > MAX_CHARS) {
            tv.setText(tv.text.subSequence(tv.length() - MAX_CHARS, tv.length()))
        }
    }

    override fun onDestroy() {
        flushJob?.cancel()
        super.onDestroy()
    }

    companion object {
        const val MODE_SETUP = 0
        const val MODE_BUILD = 1
        const val MODE_IMPORT = 2
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ZIP = "zip"
        private const val MAX_CHARS = 160_000

        fun newIntent(ctx: android.content.Context, mode: Int, zipPath: String? = null): Intent =
            Intent(ctx, TerminalActivity::class.java)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_ZIP, zipPath)
    }
}

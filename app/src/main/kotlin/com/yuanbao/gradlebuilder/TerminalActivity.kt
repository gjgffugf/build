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
    private val pending = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(b.root)
        mode = intent.getIntExtra(EXTRA_MODE, MODE_SETUP)

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { requestExit() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = requestExit()
        })

        // 补齐本次会话已有的输出
        b.tvLog.text = ExecEngine.snapshot().joinToString("\n")
        startFlushLoop()
        lifecycleScope.launch { ExecEngine.live.collect { pending.append(it).append('\n') } }

        b.btnBackHome.setOnClickListener { requestExit() }

        // 构建期间保活
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, ExecService::class.java))
        }

        startFlow()
    }

    // ---------- 流程 ----------

    private fun startFlow() {
        if (ExecEngine.isRunning()) {
            setRunning(true)
            return
        }
        ExecEngine.clearLog()
        b.tvLog.text = ""
        setRunning(true)
        job = lifecycleScope.launch {
            val exit = runFlow()
            setRunning(false)
            onFinish(exit)
        }
    }

    private suspend fun runFlow(): Int {
        ExecEngine.log("[环境] 架构：${BootstrapInstaller.abiName()}，PREFIX=${Env.prefix}")
        if (!BootstrapInstaller.ensureInstalled { ExecEngine.log(it) }) return -1

        if (!Env.isInstalled()) {
            ExecEngine.log("[环境] 开始安装 Gradle 与 JDK…")
            Env.secondStageScript()?.let {
                ExecEngine.log("[环境] 执行 bootstrap 第二阶段初始化…")
                ExecEngine.run("bash ${it.absolutePath}")
            }
            for (cmd in setupCommands()) {
                val code = ExecEngine.run(cmd)
                if (code != 0) return code
            }
            Prefs.envReady = true
            ExecEngine.log("[环境] 安装完成")
            if (mode == MODE_SETUP) return 0
        } else {
            ExecEngine.log("[环境] Gradle 与 JDK 已就绪，跳过安装")
            if (mode == MODE_SETUP) return 0
        }
        return runBuild()
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

    private suspend fun onFinish(exit: Int) {
        val exported = if (exit == 0 && mode == MODE_BUILD) {
            withContext(Dispatchers.IO) {
                Prefs.treeUri?.let { WorkspaceSync.exportOutputs(this@TerminalActivity, Uri.parse(it)) }
                    ?: emptyList()
            }
        } else emptyList()

        if (isFinishing || isDestroyed) return

        when {
            mode == MODE_SETUP && exit == 0 ->
                showResult(getString(R.string.setup_done_title), getString(R.string.setup_done_msg))
            mode == MODE_SETUP ->
                showResult(getString(R.string.setup_fail_title), getString(R.string.setup_fail_msg))
            exit == 0 -> {
                val msg = if (exported.isEmpty())
                    "构建成功。\n若项目不是 Android 工程，产物通常在 build/libs 下。"
                else
                    "构建成功！\n产物：${exported.joinToString(", ")}\n已导出到工作目录下的 builder-output/"
                showResult(getString(R.string.build_ok_title), msg)
            }
            else -> showResult(getString(R.string.build_fail_title), getString(R.string.build_fail_msg))
        }
    }

    private fun showResult(title: String, msg: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton(R.string.back_home) { _, _ -> doExit() }
            .show()
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
        runCatching { stopService(Intent(this, ExecService::class.java)) }
        finish()
    }

    // ---------- UI ----------

    private fun setRunning(running: Boolean) {
        b.progress.visibility = if (running) View.VISIBLE else View.GONE
        b.tvStatus.text = if (running) getString(R.string.running) else getString(R.string.idle)
        b.btnBackHome.text = getString(R.string.back_home)
    }

    private fun startFlushLoop() {
        flushJob?.cancel()
        flushJob = lifecycleScope.launch {
            while (isActive) {
                delay(150)
                if (pending.isNotEmpty()) {
                    val text = pending.toString()
                    pending.setLength(0)
                    b.tvLog.append(text)
                    trim()
                    b.scroller.post { b.scroller.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
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
        private const val EXTRA_MODE = "mode"
        private const val MAX_CHARS = 160_000

        fun newIntent(ctx: android.content.Context, mode: Int): Intent =
            Intent(ctx, TerminalActivity::class.java).putExtra(EXTRA_MODE, mode)
    }
}

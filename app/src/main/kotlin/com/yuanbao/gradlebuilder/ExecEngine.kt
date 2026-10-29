package com.yuanbao.gradlebuilder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 真实的进程执行引擎：启动 bash，实时把 stdout/stderr 推给界面。
 */
object ExecEngine {

    private const val MAX_HISTORY = 5000

    @Volatile
    private var process: Process? = null

    private val history = Collections.synchronizedList(ArrayList<String>(256))

    /** 实时日志流（新订阅者通过 snapshot() 补齐历史） */
    val live = MutableSharedFlow<String>(extraBufferCapacity = 512)

    private val seq = AtomicInteger(0)

    fun isRunning(): Boolean = process != null

    fun snapshot(): List<String> = synchronized(history) { ArrayList(history) }

    fun clearLog() = synchronized(history) { history.clear() }

    fun log(line: String) = push(line)

    private fun push(line: String) {
        synchronized(history) {
            history.add(line)
            if (history.size > MAX_HISTORY) history.removeAt(0)
        }
        live.tryEmit(line)
    }

    /**
     * @param cmd shell 命令
     * @param dir 工作目录（真实文件路径）
     * @return 退出码
     */
    suspend fun run(
        cmd: String,
        dir: File? = null,
        env: Map<String, String> = Env.baseEnv()
    ): Int = withContext(Dispatchers.IO) {
        val bash = Env.bash
        if (!bash.exists()) {
            push("[错误] 运行环境未安装，请先点击“部署环境”")
            return@withContext -1
        }

        // 单条命令用 exec 替换掉 bash 自身，便于强制停止时精准杀掉目标进程
        val needsWrap = !cmd.contains("&&") && !cmd.contains("||") &&
            !cmd.contains(";") && !cmd.contains("|")
        val script = if (needsWrap) "exec $cmd" else cmd

        push("\$ ${cmd.trim()}")

        val pb = ProcessBuilder(bash.absolutePath, "-lc", script)
        pb.environment().apply {
            clear()
            env.forEach { (k, v) -> put(k, v) }
        }
        if (dir != null && dir.exists()) pb.directory(dir)
        pb.redirectErrorStream(true)

        val p = try {
            pb.start()
        } catch (e: Exception) {
            push("[错误] 无法启动进程：${e.message}")
            return@withContext -1
        }
        process = p

        val reader = p.inputStream.bufferedReader()
        try {
            while (true) {
                val line = reader.readLine() ?: break
                push(line)
            }
            p.waitFor()
        } catch (e: Exception) {
            push("[中断] ${e.message}")
            -1
        } finally {
            runCatching { reader.close() }
            runCatching { p.destroy() }
            if (process === p) process = null
        }
    }

    /** 强制停止当前构建（包括其子进程） */
    fun stop() {
        val p = process ?: return
        process = null
        val pid = try {
            p.javaClass.getMethod("pid").invoke(p) as? Int ?: -1
        } catch (e: Exception) {
            -1
        }
        try {
            if (pid > 0) {
                // 先杀子进程，再杀主进程
                Runtime.getRuntime().exec(
                    arrayOf(
                        Env.bash.absolutePath, "-lc",
                        "pkill -9 -P $pid 2>/dev/null; kill -9 $pid 2>/dev/null; true"
                    )
                )
            }
        } catch (e: Exception) {
            // ignore
        }
        runCatching { p.destroyForcibly() }
        push("[已停止] 构建被用户强制终止")
    }

    fun nextId(): Int = seq.incrementAndGet()
}

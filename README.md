# GradleBuilder v1.0

一个在 Android 手机上**真实运行 Gradle**的构建器 App：自带终端界面，能装 JDK + Gradle、能选目录、能跑 `gradlew`，构建过程在终端里实时滚动输出。

## 一、功能对照

| 需求 | 实现位置 |
| --- | --- |
| 首次进入屏幕中间三个按钮 | `activity_main.xml`：选择工作目录 / 部署环境 / 构建应用 |
| 选择工作目录 → 系统文件夹授权 → 自动用源码构建 | `MainActivity.pickDirLauncher`（`ACTION_OPEN_DOCUMENT_TREE` + 持久化授权）→ `WorkspaceSync.sync()` → 自动跳转终端构建 |
| 部署环境 → 终端页 → 自动装 gradle → 完成弹窗 → 返回主界面 | `TerminalActivity(MODE_SETUP)` + `BootstrapInstaller` + `setupCommands()` |
| 构建应用：默认命令或自定义命令 | `defaultCommand()`（`gradlew assembleDebug` / `gradle build`），自定义命令存于 `Prefs.command` |
| 构建过程终端实时显示运行过程 | `ExecEngine`（`ProcessBuilder` 启动 bash，逐行推给 `SharedFlow`）+ `activity_terminal.xml` |
| 左上角返回箭头 + 运行中弹窗「返回将强制停止当前构建」→ 取消 / 确定 | `TerminalActivity.requestExit()` |
| 使用 GitHub 构建 | `.github/workflows/build-apk.yml`：push 到 GitHub 自动编译并上传 APK |

额外赠送：可选填写 GitHub 仓库地址，构建前自动 `git clone --depth 1`。

## 二、为什么能「真构建」

Android 自身没有 JVM，无法直接跑 Gradle。本 App 的做法是**在自己私有目录里搭一套真实的 Linux 用户态**：

1. 首次「部署环境」时，从 **termux/termux-packages** 的 release 下载 `bootstrap-<abi>.zip`
   （形如 `.../download/bootstrap-2026.10.25-r1%2Bapt.android-7/bootstrap-aarch64.zip`，注意 `+` 要编码成 `%2B`）
   到 `filesDir`，解压成 `$PREFIX=/data/data/包名/files/usr`（含 `bash`、`apt`、`dpkg`）；
   版本号通过 GitHub API 动态获取最新，取不到时用内置版本列表，并自动尝试 ghfast / gh-proxy 等加速镜像；
2. 用 `ProcessBuilder` 以 `bash -lc` 启动进程，注入 `PATH / LD_LIBRARY_PATH / HOME / TMPDIR / JAVA_HOME` 等环境变量；
3. 通过 `apt` 安装 `openjdk-17`、`gradle`、`git`、`procps`——这些都是真实的 Linux 包；
4. 源码从用户授权的目录同步到 `filesDir/workspace/project`（真实路径），Gradle 在这个真实目录里读写 `build/`，构建完把 APK/AAB 导回用户目录的 `builder-output/`。

停止构建时 `pkill -9 -P <pid>` + `destroyForcibly()`，Java 进程会被真正杀掉。

## 三、自己编译

```bash
# 本地（需本机已装 Gradle 8.2；仓库未内置 gradle-wrapper.jar）
gradle wrapper --gradle-version 8.2 --distribution-type bin   # 首次执行一次，生成 gradlew
./gradlew assembleDebug

# 或用 Android Studio 直接打开本目录，Sync 后 Run 即可

# 或推到 GitHub，Actions 会自动编译并产出 app-debug.apk（见 .github/workflows/build-apk.yml）
git init && git add . && git commit -m "init" && git remote add origin <你的仓库> && git push -u origin main
```

## 四、使用步骤

1. 安装 APK，授予通知权限；
2. 点 **部署环境** → 终端自动下载 bootstrap（约 30–60MB，需联网）→ 自动 `apt install openjdk-17 gradle` → 弹「安装完成」→ 点返回主界面；
   - 如果所有下载源都失败：主界面底部可填自定义下载源 URL，或点 **导入运行环境(离线)** 直接选中下载好的 `bootstrap-aarch64.zip`；
3. 点 **选择工作目录** → 在系统文件夹选择器里选中项目根目录并授权 → 自动同步源码并开始构建；
4. 也可在主界面底部填写自定义命令（如 `./gradlew :app:assembleRelease`）、仓库地址、Android SDK 路径；
5. 构建中左上角返回（或系统返回键）会弹窗提示将强制停止，确定即终止；
6. 成功后 APK 会写回你授权目录下的 `builder-output/`。

## 五、已知限制

- 构建 **Android 工程**需要 Android SDK（含 `platforms/`、`build-tools/`）。请在主界面填写 SDK 路径，或把 SDK 放到 `$HOME/android-sdk`；纯 Java / Kotlin JVM 工程无此要求。
- 非 `arm64-v8a` 设备也能用，但 Termux 上游对 arm64 支持最完整。
- 手机上构建耗内存，Gradle 已强制 `--no-daemon`；大项目建议只跑单模块任务。
- 设备需能访问 GitHub 与 Termux 软件源。

## 六、目录结构

```
app/src/main/kotlin/com/yuanbao/gradlebuilder/
├── App.kt                 Application + 通知渠道
├── Prefs.kt               工作目录/命令/仓库/SDK 等持久化
├── Env.kt                 PREFIX、HOME、JAVA_HOME、ANDROID_HOME 等环境
├── BootstrapInstaller.kt  下载解压 Termux bootstrap、建符号链接
├── ExecEngine.kt          真实进程执行 + 实时日志 + 强制停止
├── ExecService.kt         构建期间前台保活
├── WorkspaceSync.kt       SAF 目录同步 / 产物导回
├── MainActivity.kt        三个主按钮
└── TerminalActivity.kt    终端页、部署流程、构建流程、返回拦截
```

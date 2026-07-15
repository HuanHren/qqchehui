# QQ 防撤回 LSPosed 模块 v2.0

目标环境：

- Android 15
- QQ 9.2.10
- LSPosed Zygisk
- QQ 包名：`com.tencent.mobileqq`

## 功能目标

QQ 的撤回处理入口继续正常执行，因此“对方撤回了一条消息”小灰条仍有机会正常生成；模块只尝试阻止撤回调用链中负责移除原消息的 `V(...)` / `Z(...)` 方法。

## 相比原脚本的改进

- 使用标准 LSPosed/Xposed 模块入口。
- 使用 `ThreadLocal` 撤回上下文，不再使用跨线程全局 `revokeDepth`。
- 只 Hook 符合参数签名的 `k / V / Z` 重载，不拦截同名的无关方法。
- 尝试用 `msgUid / uniseq / shmsgseq / msgSeq / seq / msgId / time` 等字段匹配撤回目标与原消息。
- 提供“兼容模式”：精确标识匹配失败时，只在同一线程撤回调用链内拦截。
- 根据目标方法返回类型设置安全默认返回值。
- 所有 Hook 回调都有异常保护，发生解析错误时优先放行 QQ 原调用，减少崩溃风险。
- 不再使用完全跳过 `msgRevokeRsp` 的备用方案，因为那会连小灰条一起阻止。
- 日志不读取或打印聊天正文。

## 编译

### Android Studio

1. 用 Android Studio 打开本目录。
2. 使用 JDK 17。
3. 安装 Android SDK Platform 35。
4. 执行 `Build > Build APK(s)`。

### Windows PowerShell

```powershell
Set-Location -LiteralPath "你的工程目录"
.\build-debug.ps1
```

首次运行 `gradlew.bat` 会自动下载 Gradle 8.9。生成文件：

```text
app\build\outputs\apk\debug\app-debug.apk
```

本工程内置的是 **compile-only Xposed API stubs**。它们只用于编译，不会打进 APK；运行时由 LSPosed 提供真实 Xposed API，避免出现“Xposed API classes are compiled into the module APK”的问题。

## 安装与启用

1. 安装生成的 APK。
2. 打开 LSPosed 管理器。
3. 启用“QQ 防撤回”。
4. 作用域只勾选 QQ：`com.tencent.mobileqq`。
5. 打开模块 App，保留推荐设置。
6. 强制停止 QQ，然后重新打开。
7. 在 LSPosed 日志中搜索 `[QQAntiRevoke]`。

也可以执行：

```powershell
adb shell am force-stop com.tencent.mobileqq
```

## 判断是否成功

启动 QQ 后，日志至少应出现：

```text
[QQAntiRevoke] 宿主 QQ=9.2.10(...)
[QQAntiRevoke] 找到类 com.tencent.imcore.message.BaseMessageManager
[QQAntiRevoke] Hook 撤回入口 ...#k[...]
[QQAntiRevoke] Hook 单条移除 ...#V[...]
```

发生撤回时，应出现：

```text
[QQAntiRevoke] 进入撤回链路
[QQAntiRevoke] 阻止单条消息移除：精确标识匹配 ...
```

或：

```text
[QQAntiRevoke] 阻止单条消息移除：兼容模式（撤回线程内）
```

## 当前限制

QQ 是闭源且高度混淆的应用。`k / V / Z` 以及类路径是否在 QQ 9.2.10 中完全一致，必须通过该版本的真实 DEX 或真机 LSPosed 日志确认。因此：

- 工程结构和代码可以编译。
- LSPosed 可以识别模块并注入 QQ。
- 具体防撤回效果不能仅凭原脚本保证。
- 如果日志显示类存在但 Hook 数量为 0，需要根据 9.2.10 的真实方法签名更新 `MethodSelectors.java`。
- 如果日志完全找不到类，需要分析 QQ 9.2.10 APK 的 DEX，重新定位撤回处理类。

测试时先使用不重要的消息。QQ 更新版本后应重新验证，不要默认继续兼容。

## 使用 GitHub Actions 在手机上自动编译 APK

工程已经包含：

```text
.github/workflows/build-apk.yml
```

工作流会在以下情况自动编译：

- 向 `main` 或 `master` 分支上传/推送代码；
- 在 GitHub 的 Actions 页面手动点击 `Run workflow`。

它会使用 JDK 17、Android SDK 35 和 Gradle 8.9 构建一个已使用调试证书签名、可直接安装的 APK：

```text
QQAntiRevoke-v2.0.0-QQ9.2.10-debug.apk
```

### 手机端上传步骤

1. 在手机文件管理器中解压源码包。
2. 在 GitHub 新建一个仓库，例如 `QQAntiRevoke`。
3. 把解压目录里面的内容上传到仓库根目录。仓库根目录必须能直接看到：

```text
.github/
app/
xposed-stubs/
build.gradle
settings.gradle
gradlew
```

不要只上传源码 ZIP，GitHub Actions 不会自动解压仓库中的 ZIP。

4. 上传完成后打开仓库的 `Actions` 页面。
5. 打开 `Build Installable APK`。
6. 等待运行状态变成绿色对勾。
7. 打开该次运行，在页面顶部的构建摘要里点击 `点击下载 APK`；也可以在页面底部的 Artifacts 区域下载。

### 手动重新编译

在手机浏览器中依次打开：

```text
仓库 → Actions → Build Installable APK → Run workflow → Run workflow
```

### 构建结果说明

- APK 使用 Android 的 Debug 签名，因此可以直接安装测试。
- 构建产物保留 30 天，过期后重新运行工作流即可。
- 同一分支连续上传多次时，旧的未完成构建会自动取消，只保留较新的构建。
- 工作流同时生成 SHA-256 校验值，可在构建摘要或 `SHA256SUMS.txt` 中查看。

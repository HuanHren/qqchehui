# QQ 防撤回 LSPosed 模块 v2.1

目标环境：

- Android 15
- QQ 9.2.10（11310）
- LSPosed Zygisk
- QQ 包名：`com.tencent.mobileqq`

## 功能目标

QQ 的撤回处理入口继续正常执行，因此“对方撤回了一条消息”小灰条仍有机会正常生成；模块只尝试阻止撤回调用链中负责移除原消息的 `V(...)` / `Z(...)` 方法。

## v2.1 更新

- App 内新增“模块专属日志”。
- Hook 进程会把 `[QQAntiRevoke]` 日志单独发送到模块 App。
- 专属日志存储在模块 App 的私有目录，不会混入其他 LSPosed 模块。
- App 支持刷新、复制和清空日志。
- 日志文件达到约 1 MB 后自动轮换，避免无限增长。
- 日志不读取或记录聊天正文。

安装 v2.1 后，必须强制停止并重新打开 QQ，新的专属日志通道才会生效。旧版 LSPosed 混合日志不会自动导入 App。

## Hook 改进

- 使用标准 LSPosed/Xposed 模块入口。
- 使用 `ThreadLocal` 撤回上下文，不再使用跨线程全局 `revokeDepth`。
- 只 Hook 符合参数签名的 `k / V / Z` 重载。
- 尝试用 `msgUid / uniseq / shmsgseq / msgSeq / seq / msgId / time` 等字段匹配撤回目标与原消息。
- 提供兼容模式：精确标识匹配失败时，只在同一线程撤回调用链内拦截。
- 根据目标方法返回类型设置安全默认返回值。
- 所有 Hook 回调都有异常保护，解析失败时优先放行 QQ 原调用。

## 安装与查看日志

1. 从 GitHub Actions 下载并安装 `QQAntiRevoke-v2.1.0-QQ9.2.10-debug.apk`。
2. 在 LSPosed 中启用“QQ 防撤回”。
3. 作用域只勾选 QQ：`com.tencent.mobileqq`。
4. 打开模块 App，开启“启用防撤回”“兼容模式”和“详细诊断日志”。
5. 强制停止 QQ 后重新打开。
6. 测试一次撤回。
7. 返回模块 App，在“模块专属日志”区域点击“刷新”。

也可以通过 ADB 强制停止 QQ：

```powershell
adb shell am force-stop com.tencent.mobileqq
```

正常启动后，App 日志至少应出现：

```text
[QQAntiRevoke] 模块专属日志通道已连接
[QQAntiRevoke] 宿主 QQ=9.2.10(...)
[QQAntiRevoke] 找到类 com.tencent.imcore.message.BaseMessageManager
[QQAntiRevoke] Hook 撤回入口 ...#k[...]
[QQAntiRevoke] Hook 单条移除 ...#V[...]
[QQAntiRevoke] 安装完成：撤回入口=1，移除方法=3
```

发生撤回时，应关注：

```text
[QQAntiRevoke] 进入撤回链路
[QQAntiRevoke] 阻止单条消息移除：精确标识匹配 ...
```

或者：

```text
[QQAntiRevoke] 阻止单条消息移除：兼容模式（撤回线程内）
```

## GitHub Actions 自动编译

工作流位于：

```text
.github/workflows/build-apk.yml
```

向 `main` 推送代码或在 Actions 页面手动点击 `Run workflow`，会自动使用 JDK 17、Android SDK 35 和 Gradle 8.9 构建已签名的 Debug APK：

```text
QQAntiRevoke-v2.1.0-QQ9.2.10-debug.apk
```

构建产物保留 30 天，并附带 `SHA256SUMS.txt`。

## 本地编译

需要 JDK 17 和 Android SDK Platform 35：

```powershell
.\build-debug.ps1
```

生成位置：

```text
app\build\outputs\apk\debug\app-debug.apk
```

工程通过 `compileOnly 'de.robv.android.xposed:api:82'` 编译，Xposed API 不会打包进 APK，运行时由 LSPosed 提供。

## 当前限制

QQ 是闭源且高度混淆的应用。虽然 QQ 9.2.10 中已确认 `BaseMessageManager`、`C2CMessageManager`、`k/V/Z` 方法存在并能被 Hook，但当前防撤回没有生效，仍需通过撤回发生时的专属日志继续定位：

- 没有“进入撤回链路”：当前 `k()` 不是实际撤回入口。
- 有“进入撤回链路”但没有“阻止移除”：删除可能是异步或跨线程执行。
- 已显示“阻止移除”但消息仍消失：QQ 可能还有数据库、缓存、状态替换或同步路径。

请先使用不重要的消息测试。QQ 更新后需要重新验证兼容性。

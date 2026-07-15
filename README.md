# QQ 防撤回 NT v3.0

面向以下测试环境：

- Android 15
- QQ 9.2.10（versionCode 11310）
- LSPosed Zygisk
- QQ 包名：`com.tencent.mobileqq`

## v3.0 为什么重建

v2.x 依赖 `BaseMessageManager.k/V/Z` 的同步调用链。真机日志证明这些方法虽然可以 Hook，但 QQ 9.2.10 的实际撤回主要走 QQ NT 内核推送链路，因此 v2.x 无法可靠阻止消息消失。

v3.0 删除了原来的 `V/Z + ThreadLocal` 方案，改为 Hook：

```text
com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession$CppProxy.onMsfPush
```

目前识别两类 QQ NT 命令：

```text
trpc.msg.olpush.OlPushService.MsgPush
trpc.msg.register_proxy.RegisterProxy.InfoSyncPush
```

在线撤回通过 `ContentHead.type/sub_type` 判断：

- 单聊撤回：`528 / 138`
- 群聊撤回：`732 / 17`

对在线撤回，模块在 `onMsfPush` 执行前终止该次撤回推送。对启动或重连时的 `InfoSyncPush`，模块只移除顶层字段 8（`sync_msg_recall`），其他同步字段原样保留。

同时保留一个精确签名的旧链路备用入口：

```text
BaseMessageManager.k(ArrayList, boolean)
```

备用入口只在真实调用时直接阻断整个撤回处理，不再拦截普通消息删除方法。

## 模块专属日志

v3.0 不再使用跨应用广播保存日志，而是使用导出的 `ContentProvider.call()`：

```text
content://com.huanhren.qqantirevoke.logs
```

Provider 会校验调用 UID，只接受 QQ 与模块 App 自身。模块 App 提供：

- 刷新日志
- 复制日志
- 清空日志
- 测试日志通道

日志文件只接收以 `[QQAntiRevoke]` 开头的内容，达到约 1 MiB 后自动轮换。

## 编译

GitHub Actions 会在推送到 `main` 后自动执行：

```text
:app:testDebugUnitTest
:app:assembleDebug
```

单元测试覆盖：

- NT 单聊撤回 protobuf
- NT 群聊撤回 protobuf
- `InfoSyncPush.sync_msg_recall` 字段移除
- 普通非撤回推送放行

构建产物：

```text
QQAntiRevoke-NT-v3.0.0-QQ9.2.10-debug.apk
```

手机端下载路径：

```text
仓库 → Actions → Build Installable APK → 最新绿色构建 → Artifacts
```

## 安装与测试

1. 覆盖安装 v3.0 APK。
2. 打开模块 App，点击“测试日志通道”。
3. 确认页面出现 `App 日志 Provider 自检成功`。
4. 在 LSPosed 中启用模块，作用域只勾选 QQ。
5. 强制停止 QQ，再重新打开。
6. 返回模块 App 刷新日志。
7. 确认出现：

```text
v3.0 模块专属日志 Provider 已连接
Hook NT 推送入口 ...onMsfPush...
v3.0 安装完成：NT onMsfPush=1...
```

8. 让另一个账号撤回一条普通文字消息，再刷新日志。

命中在线撤回时应出现：

```text
检测到 NT 在线撤回推送
已阻断 NT 在线撤回原处理
```

命中同步撤回时应出现：

```text
检测到 NT 同步撤回数据
已从 InfoSyncPush 移除 sync_msg_recall 字段
```

## 当前限制

v3.0 是 Java 层 QQ NT 推送拦截版本，还没有集成 QAuxiliary 使用的 `libkernel.so` ARM64 原生 inline hook。因此：

- 如果 QQ 在 `onMsfPush` 之前已经由 native 内核完成删除，Java 层阻断可能仍不足。
- 如果 v3.0 能记录撤回但消息仍消失，下一阶段必须进入 NDK/ARM64 原生 Hook，而不是重新扩大 Java 删除方法拦截。
- 当前只针对 QQ 9.2.10 验证，不承诺兼容其他 QQ 版本。
- 当前优先保证原消息不被删除，暂未重建 QAuxiliary 那种可点击的 NT 本地灰条。

## 技术参考与许可说明

NT 撤回命令、消息类型判断和整体分层思路参考了 [QAuxiliary](https://github.com/cinit/QAuxiliary) 的公开实现。QAuxiliary 使用 AGPLv3 并附带项目 EULA。本仓库没有复制其完整防撤回类、native inline-hook 实现或 UI 框架，而是基于公开协议字段独立编写最小实现。进一步复制或移植其源码前，必须重新审查并遵守其许可证要求。

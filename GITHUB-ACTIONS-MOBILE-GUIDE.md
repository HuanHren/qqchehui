# 手机端使用 GitHub Actions 编译 v3.0 APK

仓库已经包含完整源码与自动构建工作流，不需要再上传 ZIP。

## 自动构建

每次 `main` 分支更新后，GitHub 会自动运行：

```text
Actions → Build Installable APK
```

工作流会先执行 NT 撤回 protobuf 单元测试，再编译 Debug APK。

## 下载 APK

1. 打开仓库的 `Actions` 页面。
2. 进入最新的 `Build Installable APK`。
3. 等待状态变成绿色对勾。
4. 打开运行记录，在 `Artifacts` 下载：

```text
QQAntiRevoke-NT-v3.0.0-QQ9.2.10-debug
```

下载的是 ZIP，解压后得到：

```text
QQAntiRevoke-NT-v3.0.0-QQ9.2.10-debug.apk
```

APK 使用 GitHub Actions 生成的 Debug 签名，可直接覆盖安装之前的 Debug 版本。

## 手动重新构建

```text
仓库 → Actions → Build Installable APK → Run workflow → Run workflow
```

构建产物保留 30 天。过期后重新运行工作流即可。

## 安装后的第一步

1. 打开 `QQ 防撤回 NT` App。
2. 点击“测试日志通道”。
3. 确认出现 `App 日志 Provider 自检成功`。
4. 强制停止并重新打开 QQ。
5. 返回模块 App，刷新日志。

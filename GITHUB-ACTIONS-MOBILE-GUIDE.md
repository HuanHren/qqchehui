# 手机上传 GitHub 后自动编译 APK

## 你要上传什么

先解压源码包，然后把源码目录里面的文件和文件夹上传到 GitHub 仓库根目录。

仓库首页必须直接看到：

```text
.github
app
xposed-stubs
build.gradle
settings.gradle
gradlew
```

不能只把 ZIP 文件上传到仓库。

## 自动编译

上传到 `main` 或 `master` 分支后，GitHub 会自动运行：

```text
Actions → Build Installable APK
```

构建成功会显示绿色对勾。

## 下载 APK

打开成功的工作流运行记录，在构建摘要中点击：

```text
点击下载 APK
```

生成文件：

```text
QQAntiRevoke-v2.0.0-QQ9.2.10-debug.apk
```

该 APK 已使用 Debug 证书签名，可以直接安装测试。

## 手动重新构建

```text
仓库 → Actions → Build Installable APK → Run workflow
```

构建文件保留 30 天，过期后重新运行即可。

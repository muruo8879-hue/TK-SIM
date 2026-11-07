# TK SIM

TK SIM 是一个离线 Android 管理器。它使用 [LSPatch](https://github.com/JingMatrix/LSPatch/tree/v1.2)，把 Xposed 模块嵌入用户自己设备上的 TikTok 安装包。生成的 TikTok 保留原包名；继续使用同一私有签名时，Android 可以按正常的同签名升级保留应用数据。

**本仓库不包含 TikTok APK 和签名私钥。** 请使用你自己设备上从可信来源取得的 TikTok 安装包。请不要把从 TikTok 提取的 APK 分包提交到公开仓库。

## 功能

- **TikTokSIMSpoof v1.0.0**：只在已修补的应用进程内 Hook 部分 `TelephonyManager` 与 `SubscriptionManager` Java 接口。隐藏模式会将部分接口替换为 SIM 不存在、空运营商标识或空订阅列表。不会禁用实体 SIM。
- **Google 网页登录兼容模块**：本地编写的 Xposed 模块 Hook TikTok 内部方法 `com.bytedance.lobby.google.GoogleAuth.isAvailable()` 并返回 `false`。在测试过的 TikTok 47.6.3 上，这使 TikTok 使用其自己的 Google 网页登录回退流程。模块启动时也会做只读 SIM 接口检查，日志只写入检查结果布尔值，不记录国家、运营商或订阅内容。

模块更新流程：导入模块 APK，点“更新当前 TikTok 的模块”，等待手机本地修补完成，再交给 Android 安装器覆盖安装。模块被嵌入 TikTok APK，不是运行时热更新。管理器没有网络权限，不联网下载 TikTok 或模块，也没有 Root 或 Shizuku 依赖。它只声明 `REQUEST_INSTALL_PACKAGES`，由 Android 显示安装确认。

## 技术原理

1. 从已安装应用读取 TikTok base APK 和各个 split APK；也可以导入完整安装包。
2. 检查 APK 签名、包名、分包名称和版本。若输入已经修补，会解出其中保留的官方原包，再次核对官方 TikTok 签名。
3. 检查模块包名及证书。当前构建只接受内置 SIM 模块和本地 Google 模块。
4. 在本机运行固定 SHA-256 的 LSPatch v1.2 引擎。它将 loader、运行资源和模块 APK 嵌入目标应用；运行时由 LSPatch 的 LSPosed/LSPlant Hook 引擎加载这些模块。
5. 用用户原有签名密钥签署所有分包，把显示名称设为 `TikTok`，再逐个验签。最后由 Android Package Installer 作为同一应用安装。

保留 TikTok 数据还要求包名和签名相同、版本不降级，并且新版自身允许数据迁移。私钥只能由应用所有者保管，不应提交或发布。源代码里的公开证书 SHA-256 不是私钥。

SIM Hook 只覆盖模块实现的 Java 方法，不隐藏 IP、账号地区、位置或已有 TikTok 数据，也不保证覆盖未 Hook 的原生或 Binder 调用。TikTok 或 Android 更新后需要重新测试。Google 登录只验证过 TikTok 47.6.3。

## 上游仓库与许可证

- [JingMatrix/LSPatch v1.2](https://github.com/JingMatrix/LSPatch/tree/v1.2)：APK 改写、内嵌 loader 和模块运行时；build 487，提交 `0dc50f42503711b14f5e2bb217c2bdd6321ce5be`。GPL-3.0。源码快照在 `third-party/`；其 git 子模块应按上游 `.gitmodules` 和各自许可证取得。
- [NZM7973/TikTokSIMSpoof v1.0.0](https://github.com/NZM7973/TikTokSIMSpoof/tree/v1.0.0)：内置 SIM 模块，提交 `f08cc9292b52c7b3ac0b4a439ce4bf2d19c4af0a`。MIT，许可文本见 `third-party/TikTokSIMSpoof-LICENSE`。
- [rovo89/XposedBridge](https://github.com/rovo89/XposedBridge)：两个模块使用的传统 `de.robv.android.xposed` API。上游仓库已归档，其许可证和声明适用于该 API。
- [Android apksig](https://android.googlesource.com/platform/tools/apksig) 与 [apkzlib](https://android.googlesource.com/platform/tools/apkzlib)：LSPatch 使用的 APK 验签与打包依赖。
- `modules/google-web-login/`：本项目本地 Google 兼容模块源码。日志有意不输出 SIM 数据，避免 Hook 失败时把运营商信息写进 logcat。

管理器和本地 Google 模块按 GPL-3.0 发布，见 `LICENSE`。上游部分各自保留原许可。准备公开源码时应保留 `third-party/` 中的许可文件和来源链接，并按 LSPatch 上游说明取得其子模块与依赖源码。

## 构建

需要 Python 3、JDK 21、Android SDK 平台 36 和 build-tools 37.0.0。

使用自己控制的密钥构建管理器：

```text
python build.py --sdk <Android-SDK> --jdk <JDK> --keystore <自己的签名密钥.p12> --password-file <密码文件> --output <TK-SIM-Manager.apk>
```

`ApkPolicy.java` 固定校验 TikTok 修补包及本地 Google 模块的签名证书。分支维护者应生成自己的签名密钥，使用同一密钥重建 Google 模块，并把自己的公开证书指纹写入 `ApkPolicy.LOCAL`。不要把私钥或密码写进源码、Git 历史或 Release 附件。

本次源码发布保留了单设备原型的证书固定校验，尚未提供自动生成密钥或运行时接受任意密钥的功能。其他用户需要修改 `ApkPolicy.LOCAL`，用自己的密钥（别名 `sim-test`）重新生成 `assets/modules/google-login.apk`，再编译管理器，最后通过管理器导入 PKCS12 文件及密码。仓库中的预编译 Google 模块使用原型作者的签名。

可通过 `JAVA_HOME`、`ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指定工具链。`--debug-setup` 只用于首次导入私有签名密钥；日常发行请构建默认的 `debuggable=false` 版本，不要公开调试 APK。

使用 `modules/google-web-login/build.ps1` 可以单独编译 Google 模块，签名别名为 `sim-test`。编译需提供 Xposed API 82 JAR，可从 [api.xposed.info](https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar) 取得。

`tests/PolicyTest.java` 是小型验签与输入校验测试。运行需自行提供原始 TikTok APK 测试夹具；仓库不包含 TikTok 安装包。

## 版本与验证

- 管理器 1.0.1 的显示名为 **TK SIM**。
- 管理器生成的 TikTok 显示名为 **TikTok**。
- 已使用 TikTok 47.6.3 验证手机本地修补、同签名覆盖和登录数据保留。1.0.1 的名称和日志修改已通过构建与验签，尚未进行设备回归测试。
- 此版本不承诺兼容未实测的 TikTok 版本。

## 相关链接

- [LINUX DO 社区](https://linux.do/)

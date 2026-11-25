# 拾证版本索引

## v0.2.0 · 2026-11-25

| 项目 | 值 |
| --- | --- |
| 构建提交 | `ccc495bae0e337a2c6973c904aa2b3f500981832`，与 APK 内 Git 信息一致 |
| Git 标签 | `v0.2.0`，包含交付文档 |
| 私测 APK | `releases/v0.2.0/shizheng-0.2.0.apk` |
| APK SHA-256 | `754f9b987228a7f13c655668c5b4fe8dc68e290380e06d5d642e98b01fb05d9c` |
| 应用信息 | `app.shizheng`，版本 `0.2.0`（版本码 2），Android 9 及以上 |
| 签名 | 沿用 0.1.0 分发签名，RSA 3072，APK Signature Scheme v3 |
| 本地归档 | `releases/v0.2.0/shizheng-v0.2.0.bundle`、`SHA256SUMS.txt`、`verification/` |

可覆盖安装 0.1.0。应用会迁移到 Room 2，并可读取旧格式备份。按原签名升级和保留旧备份可保护既有数据。私测 APK 含共享 DeepSeek 凭据，只适合受控分发。源码 bundle 不含该凭据。 [测试报告](TEST_REPORT.md)

## v0.1.0 · 2026-11-24

| 项目 | 值 |
| --- | --- |
| 构建提交 | `4d852f2c85ec54342d30f7ca58b9bb81088bf0c2`，与 APK 中嵌入的 Git 信息一致 |
| 功能提交 | `83fc2e0` |
| Git 标签 | `v0.1.0`，包含交付文档 |
| APK | `releases/v0.1.0/shizheng-0.1.0.apk` |
| APK SHA-256 | `4907873d94df7623e2e56b7acaac767b1304441c1f5faccc15932f625121d1cb` |
| 应用信息 | `app.shizheng`，版本 `0.1.0`（版本码 1），Android 9 及以上 |
| 签名 | 本地专用分发签名，RSA 3072，APK Signature Scheme v3 |
| 签名证书 SHA-256 | `dc3bdb787e8e777facafc4652334fa3c3d7d6947d0908bd794bea7b9c5ea76bb` |
| 工具链 | Windows 11 x64、Microsoft OpenJDK 17.0.21.1、Gradle 8.13、AGP 8.13.2、SDK Platform 36 rev. 2、Build-Tools 35.0.0 |
| 本地归档 | `releases/v0.1.0/shizheng-v0.1.0.bundle`、`SHA256SUMS.txt`、`verification/` |

校验、功能覆盖与限制见 [测试报告](TEST_REPORT.md) 和 [变更记录](../CHANGELOG.md)。概念阶段的 `fb30d9e` 含设计 v1 和 v2，无初始化 APK。

## 分发签名

包名固定为 `app.shizheng`。同包名后续升级须使用原 PKCS#12 私钥，提升 `versionCode`，并按数据库版本迁移。调试包用 Android 调试证书且包名为 `app.shizheng.debug`。

Windows 当前账户的签名目录为 `C:\Users\1\AppData\Local\Shizheng\signing`，仓库忽略的备用副本在 `E:\ChatGPT\score note\private\signing-backup`。两处都保管 `shizheng-release.p12` 和 `password.dpapi`。后者由 Windows 当前用户的 DPAPI 加密，通常只能在原用户环境解密。可在相同账户下用脚本参数 `-SigningDirectory` 指向备用目录。两处是本机副本。

换电脑前，可在原账号用 `ConvertTo-SecureString` 从 `password.dpapi` 读出 SecureString，并通过自己的密码管理器导入口令。把 PKCS#12 存入另一处安全位置。换机时用密码管理器取回口令，在新用户环境交互式运行 `Read-Host -AsSecureString | ConvertFrom-SecureString | Set-Content <新签名目录>/password.dpapi`，再用原私钥签名。不要把口令贴到聊天、命令参数、日志或 Git。

```powershell
.\gradlew.bat :app:assembleRelease
.\scripts\Sign-Release.ps1 -InputApk app/build/outputs/apk/release/app-release-unsigned.apk -OutputApk releases/rebuild/shizheng-0.1.0.apk -AndroidSdk $env:ANDROID_HOME
```

脚本先对齐 APK，使用专用分发密钥签名，再验证签名并输出 SHA-256。选用同一 Windows 用户下的备份签名目录也可恢复签名流程。

## 重建 0.2.0 私测包

构建提交为 `ccc495bae0e337a2c6973c904aa2b3f500981832`。当前 Windows 用户的凭据输入存放在被 Git 忽略的 `private/deepseek.dpapi`。它与 PKCS#12 签名文件独立，由当前用户的 DPAPI 保护。另一台机器由有权限的分发者通过密码管理器取得有效的 DeepSeek 凭据，再用 `Read-Host -AsSecureString | ConvertFrom-SecureString | Set-Content private/deepseek.dpapi` 生成本机输入。公开构建时省略注入步骤。

```powershell
$shizhengSecret = ConvertTo-SecureString (Get-Content -LiteralPath 'private/deepseek.dpapi' -Raw).Trim()
$shizhengPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($shizhengSecret)
try {
    $env:SHIZHENG_DEEPSEEK_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($shizhengPointer)
    .\gradlew.bat --no-daemon --no-build-cache --no-configuration-cache :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
} finally {
    Remove-Item Env:SHIZHENG_DEEPSEEK_API_KEY -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($shizhengPointer)
    $shizhengSecret.Dispose()
}
.\scripts\Sign-Release.ps1 -InputApk app/build/outputs/apk/release/app-release-unsigned.apk -OutputApk releases/rebuild/shizheng-0.2.0.apk -AndroidSdk $env:ANDROID_HOME
```

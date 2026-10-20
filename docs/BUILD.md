# 构建指南

无需 Gradle / Android Studio，纯命令行构建，全程约 20 秒。

## 依赖

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17+（开发用 21） | javac / d8 / apksigner 都要用 |
| Python | 3.8+ | 构建编排脚本 |
| Android SDK build-tools | 34.0.0 | aapt2 / zipalign / d8.jar / apksigner.jar |
| Android SDK platform | android-34 | android.jar（编译期类库） |

SDK 最小安装（sdkmanager 示例）：

```bash
sdkmanager "build-tools;34.0.0" "platforms;android-34"
```

## 构建

```bash
cd app
python build_apk.py
# 产物: dist/xxt-qd-v3.0.apk（约 214 KB）
```

常用参数：

```bash
python build_apk.py -o dist/my.apk --version-code 31 --version-name 3.1
python build_apk.py --keystore path/to/my.jks --alias myalias --kspass secret
```

环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `ANDROID_SDK` | `C:\Users\<you>\android-sdk` | SDK 根目录 |
| `JAVA_HOME` | Eclipse Adoptium JDK 21 路径 | JDK 根目录 |
| `XXT_KS_PASS` | 内置开发口令 | 签名口令 |

## 流水线（build_apk.py 六步）

1. **aapt2 compile + link** — res/ 编译为 flata，与 AndroidManifest.xml、assets/（已过滤 `*.bak*`）链接成 base.apk，注入 versionCode/versionName，生成 R.java
2. **javac** — `--release 8` 编译 java/ + gen/ 全部源码
3. **d8** — `--release --min-api 21` 打成 classes.dex
4. **打包 dex** — Python zipfile 把 dex 塞回 base.apk（保留原条目压缩方式，resources.arsc 维持 STORED 以兼容 Android 11+ 的安装要求）
5. **zipalign + apksigner** — `-p -f 4` 对齐；v1+v2+v3 三重签名（keystore 缺失时 keytool 自动生成 30 年期证书）
6. **验证** — apksigner verify + aapt2 dump badging 回显

## 签名与升级兼容

正式分发使用 `tools/xxt.keystore`（**不入库**，.gitignore 排除）。
v2.x → v3.x 覆盖安装要求签名证书一致（SHA-256: `6123344e…8bf9`）。
自己 fork 构建会生成新证书，安装时需先卸载官方签名版本。

## 已知坑

- **d8 与匿名内部类**：build-tools 34 附带的 d8 处理部分匿名内部类会 NPE，代码规约是所有回调一律用静态嵌套命名类（源码里有注释标记）
- **aapt2 资源必须先 compile**：link 不接受 res/ 原始目录，需先编成 .flata
- **Windows 控制台中文乱码**：构建输出乱码不影响产物；可先 `chcp 65001`
- **assets 里的备份文件**：构建脚本自动跳过 `*.bak*` 与 `.` 开头文件，别把调试残留放进去

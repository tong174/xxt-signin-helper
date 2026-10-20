# Changelog

## v3.0（2026-10）

全面优化版。签名证书与 v2.x 一致，可直接覆盖安装。

### 修复
- **多账号代签串号**：登录前清空原生 Cookie 罐（新增 `xxt.local/clearcookies` 端点）；课程/活动缓存 key 按 uid 隔离，子账号不再误用主账号课程列表
- **子账号代签错误携带主账号人脸**：`submit()` 的 face 回退逻辑改为显式键判断，代签子账号不带主账号人脸 objectId
- **退出登录残留会话**：登出同时清原生 Cookie 罐与 WebView CookieManager
- **分享链接冷启动竞态**：固定延时 900ms 改为 `onPageFinished` 后投递 + 500ms×20 轮询兜底，慢机型不再丢分享
- **代理错误分支二次异常**：`e.getMessage()` 为 null 时不再 NPE（`safeMsg` 兜底）
- **Set-Cookie 删除语义**：识别 `Max-Age=0/-1`、1970 过期，正确从罐中移除
- **签到成功后待办不清理**：`encUse`/统一核心成功后自动移出「缺 enc 待办」；移除 `ENCC.del?0:0` 死代码

### 新增
- **会话保活**：`ensureLogin()` 探测会话失效后自动用本机凭据重登；监控连续 2 轮拉空课程 / 3 轮异常即触发
- **快速启动**：启动优先复用原生 Cookie 罐有效会话（免登录直进主界面）
- **jsQR 双引擎扫码**：系统 `BarcodeDetector` 缺失（无 GMS 国产 ROM）时自动降级到内置 jsQR 纯 JS 解码，摄像头实时扫码与相册识图全设备可用
- **深色模式**：原生注入系统 uiMode + 页面三档切换（跟随系统/浅色/深色），`onConfigurationChanged` 实时跟随
- **人脸照片压缩上传**：最长边 1280 / JPEG 85%，几 MB 原图不再塞爆代理 URL payload
- **原生代理自动重试**：IOException 类失败间隔 350ms 重试 1 次
- **息屏保活**：MonitorService 每 tick 短暂持有 PARTIAL_WAKE_LOCK（30s 超时兜底，8s 后主动释放）
- **WebView 失效提示**：页面被系统回收后通知栏提示「点此重新打开 App」
- `xxt.local/version` 端点；「关于」页显示原生壳版本
- 监控间隔新增 10 分钟档
- 监控自动签到成功同步写入历史记录（此前只计数不留痕）
- 统一签到核心 `signActivity()`：监控 / 代签 / 群签三处重复逻辑合一，二维码类活动在所有路径下均享受「enc 缓存 → 人脸换 enc → 待办」完整链路（此前代签直接跳过）

### 安全加固
- 服务器返回的活动名 / 课程名 / 位置文本统一 `esc()` 转义，消除 DOM 注入面
- `android:allowBackup="false"`：含凭据数据不可被 adb backup 提取
- WebView 关闭 `file://` / `content://` 访问；assets 服务增加 `..` 目录穿越防护
- 构建脚本自动剔除 `*.bak*` 备份文件（v2.5 曾把 73KB 的 index.bak6.html 打进 APK）
- 版本号纳入构建参数（此前 APK 元数据恒为 1.0/1，与页面显示的版本脱节）

## v2.5

- 课程源 3：`mobile3.chaoxing.com/mobile/mirrorcourseList` 镜像课程（移动端隐藏课程）
- 课程源 4：会议课程（k.chaoxing.com）
- enc 缓存（扫一次全活动复用，30 分钟 TTL）+「缺 enc 待办」清单
- 原生前台服务 MonitorService：息屏/切后台持续监控
- `xxtqd://` 分享 scheme + 分享二维码生成
- 全屏扫码（1920×1080 + 连续对焦）
- 人脸换 enc 诊断面板

## v2.3

- 群聊签到（学习通 IM / Easemob 协议，手写 protobuf 解析器）
- 人脸识别（学籍照拉取 / 照片上传 / faceImageObjectId 自动携带）
- 多账号代签
- 自动检测签到（H5 定时器版）
- `SIGNIN:` 原生二维码格式解析

## v2.2 及更早

- 登录、课程列表、活动列表、普通/手势/签到码/位置/二维码签到
- 签到码轮询获取、preSign + analysis 反爬链路
- 自绘 adaptive icon

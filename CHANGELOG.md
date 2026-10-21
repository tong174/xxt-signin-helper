# Changelog

## v3.1（2026-10-22）

签名与 v2.x/v3.0 一致，可直接覆盖安装（versionCode 31）。

### 修复
- **群聊签到 IM 登录必败**：学习通下发的 `imAccount.password` 是 DES/ECB/PKCS5 加密的十六进制串（密钥 `SL2(M/eD`，参考 ChaoxingSignFaker `resolveImPassword`），旧版直接当明文提交 100% 失败。v3.1 内置纯 JS DES 解密（加/解密、hex、PKCS5 去填充），解密失败自动回退明文尝试
- **部分机型相册选图 / 上传人脸点了没反应**：原生壳实现 `onShowFileChooser` + `onActivityResult`（WebView 默认不实现文件选择，`input[type=file].click()` 静默失败）；H5 侧 input 挂载 DOM 后再触发（游离 input 部分 WebView 不弹选择器），并支持一次多选最多 6 张
- IM token 请求兼容三种 body 形态：表单 / JSON / JSON+表单 Content-Type（新增原生代理 `mode=3` 原样透传 body）
- 全局 JS 错误捕获：`window.error` / `unhandledrejection` 直写日志面板，问题可追溯
- 群列表长文本溢出：省略号截断

### 新增
- **一键扫描群聊并签到**：3 路并发拉全部群消息 → 汇总去重 → 4 路并发拉活动详情 → 普通/位置/签到码类自动签 → 结果面板汇总；二维码类逐个提供「缓存 enc 直签 / 摄像头扫码 / 粘贴 enc」入口
- **摄像头变焦**：硬件变焦（`zoom` constraint）优先，无硬件支持时 CSS 放大 + 解码侧 ROI 裁剪同步；双指捏合缩放；变焦倍数实时显示
- **手电筒**：设备支持 torch 时一键开关
- **连续扫模式**：签完不关摄像头继续扫（同内容 8 秒去重），批量场景不用反复重开
- **远距离小码识别**：优先申请 2560×1440；jsQR 改为「中心区域原生像素 ROI（960px）」解码，不再整帧缩小
- **扫码引擎自愈**：系统 BarcodeDetector 连续 6 帧无结果自动切换 jsQR
- **相册多区域全分辨率识别**：`scanImageFile` 按整图 → 中心 → 四象限逐区域解码（1400px）；`createImageBitmap` 失败回退 `Image` + ObjectURL
- MonitorService WakeLock：每轮检测持锁 2 分钟，息屏时保证 monTick 的 JS 跑完
- 原生 `onConsoleMessage` → logcat（tag `xxtjs`）调试转发
- 构建脚本 `--vcode/--vname` 参数化

### 继承 v3.0（H5 层全量保留）
统一签到核心 `signActivity()`、会话保活自动重登、快速启动免登录、课程/活动缓存按 uid 隔离、登录前清 Cookie、人脸照片压缩上传、深色模式、XSS 转义、jsQR 内置引擎等。

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

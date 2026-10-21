# 学习通签到协议笔记

本 App 实际调用的全部接口与关键参数。协议知识来自公开开源项目
（cxOrz/chaoxing-signin、MetaQiu/xxtSignApi、ChaoxingSignFaker）与自行抓包验证。

所有请求经原生代理发出，自动携带会话 Cookie，UA 伪装为 Chrome Mobile。

## 1. 登录

```
POST https://passport2.chaoxing.com/fanyalogin
Content-Type: application/x-www-form-urlencoded

fid=-1&uname=<账号>&password=<密码明文>
&refer=https%3A%2F%2Fi.chaoxing.com
&t=true&forbidotherlogin=0&validate=&doubleFactorLogin=0&independentId=0
```

- 成功：`{"status":true,...}`，Set-Cookie 下发 `_uid / UID / fid / vc3 / _d / DSSTASH_LOG` 等
- 备选登录接口（本项目未用）：`passport2-api.chaoxing.com/v11/loginregister`，其 `code` 参数为 DES/ECB/PKCS7 加密密码（iv `u2oh6Vu^HWe40fj`）
- 会话有效期通常数天～数周；过期后需重登（v3.0 自动完成）

## 2. 课程列表（四源合并）

| 源 | 接口 | 说明 |
|---|---|---|
| 1 | `GET https://mooc1.chaoxing.com/mycourse/backclazzdata?view=json&rss=1` | 主源，`channelList[].content` 里取 courseId/classId |
| 2 | `POST https://mooc1-1.chaoxing.com/visit/courselistdata`（form: `courseType=1&courseFolderId=0&baseEducation=0&superstarClass=0&pageSize=1000`） | 返回 HTML，正则抽 `courseId="..." clazzId="..."` |
| 3 | `GET https://mobile3.chaoxing.com/mobile/mirrorcourseList?fid=<fid>&uid=<uid>` | **镜像课程**（移动端隐藏课），`data[].clazz.data[]`，classId 取 `id` 按 `_` 分割的末段 |
| 4 | `GET https://k.chaoxing.com/pc/meet/index_mymeet_v2?pageSize=999&page=1` | 会议课程（可选） |

按 `courseId_classId` 去重合并。结果缓存 10 分钟（v3.0 起按 uid 隔离）。

## 3. 签到活动

```
GET https://mobilelearn.chaoxing.com/v2/apis/active/student/activelist
    ?fid=0&courseId=<c>&classId=<k>&showNotStartedActive=0&_=<ts>
```

`data.activeList[]`，`status===1` 为进行中。类型 `otherId`：

| otherId | 类型 |
|---|---|
| 0 | 普通签到 |
| 2 | 二维码签到 |
| 3 | 手势签到 |
| 4 | 位置签到 |
| 5 | 签到码签到 |

活动详情（取签到码 / 位置 / 人脸开关）：

```
GET https://mobilelearn.chaoxing.com/v2/apis/active/getPPTActiveInfo?activeId=<aid>&_=<ts>
```

关键字段：`signCode`（3/5 类，活动进行中才下发）、`locationText/locationLatitude/locationLongitude/locationRange`（4 类）、`attendNum`、`otherId`、`status`。

## 4. 签到链路

**任何签到提交前必须先走预签（反爬链路）：**

```
1. GET https://mobilelearn.chaoxing.com/newsign/preSign
       ?courseId=<c>&classId=<k>&activePrimaryId=<aid>&general=1&sys=1&ls=1&appType=15&tid=&uid=<uid>&ut=s
2. GET https://mobilelearn.chaoxing.com/pptSign/analysis?vs=1&DB_STRATEGY=RANDOM&aid=<aid>
       → 返回 HTML，从中截取 code='...' 的动态 code
3. GET https://mobilelearn.chaoxing.com/pptSign/analysis2?DB_STRATEGY=RANDOM&code=<code>
4. （等待约 500ms）
```

**提交签到（按类型）：**

```
# 普通 / 手势 / 签到码（签到码需先校验）
GET https://mobilelearn.chaoxing.com/pptSign/stuSignajax
    ?activeId=<aid>&uid=<uid>&clientip=&latitude=-1&longitude=-1&appType=15&fid=<fid>&name=<姓名>[&faceImageObjectId=<oid>]

# 签到码校验（失败有计数锁定，连错锁 1 小时，切勿枚举）
GET https://mobilelearn.chaoxing.com/widget/sign/pcStuSignController/checkSignCode
    ?activeId=<aid>&signCode=<code>          → {"result":1} 为有效

# 位置签到
GET .../pptSign/stuSignajax
    ?name=<姓名>&address=<locationText>&activeId=<aid>&uid=<uid>&clientip=
    &latitude=<lat>&longitude=<lng>&fid=<fid>&appType=15&ifTiJiao=1[&faceImageObjectId=<oid>]

# 二维码签到（enc 来自教师投屏二维码，10 秒一换）
GET .../pptSign/stuSignajax
    ?enc=<enc>&name=<姓名>&activeId=<aid>&uid=<uid>&clientip=
    &location={"result":"1","address":"...","latitude":0,"longitude":0,"altitude":"0"}
    &latitude=-1&longitude=-1&fid=<fid>&appType=15[&faceImageObjectId=<oid>]
```

成功返回纯文本 `success`（或含「签到成功/已签到」文案）。

## 5. 二维码与 enc

教师端投屏二维码内容格式（原生）：

```
SIGNIN:activeId=<aid>&uid=...&enc=<ENC>-<suffix>
```

解析规则（参考 ChaoxingSignFaker 的 `ChaoxingQRCodeSigner.parseQRCode`）：
- 去掉 `SIGNIN:` 前缀后，取第一个 `-` 之前为 content
- `enc` = content 中 `&enc=` 之后、下一 `&`（或结尾）之前
- `activeId` = content 第一段 `=` 后的数字；`courseId` = content 最后一段 `=` 后的值

enc 与学生账号无关，**可以共享**：现场同学扫到后把 enc / 分享链接发给远程同学即可代签（有效期内）。

## 6. 人脸

```
1. GET  https://sso.chaoxing.com/apis/login/userLogin4Uname.do        → msg.puid
2. GET  https://pan-yz.chaoxing.com/api/token/uservalid               → _token
3. POST https://pan-yz.chaoxing.com/upload?_from=mobilelearn          → objectId
       multipart: _token / puid / file
       （图片可经 https://p.ananas.chaoxing.com/star3/270_160c/<objectId>.png 预览）
4. 学籍照直取：
   GET  https://mobilelearn.chaoxing.com/v2/apis/sign/collectionfilephotoEnc?DB_STRATEGY=DEFAULT
       → data.oldObjectId / data.objectId
```

签到时携带 `faceImageObjectId=<objectId>` 即完成拍照签到要求。

**人脸换 enc**（活动开启人脸验证时可用，免扫码）：

```
GET https://mobilelearn.chaoxing.com/pptSign/check-face-result
    ?DB_STRATEGY=PRIMARY_KEY&STRATEGY_PARA=activeId
    &activeId=<aid>
    &faceResult={"currentFaceId":"<objectId>","LiveDetectionStatus":1,"collectStatus":1,"cxtime":"<ts>"}
    → 成功时返回 enc
```

## 7. 群聊签到（IM 协议）

学习通群聊走环信（Easemob）托管，`cx-dev/cxstudy` 应用：

```
1. 取 IM 账号：
   GET https://sso.chaoxing.com/apis/login/userLogin4Uname.do
       → msg.accountInfo.imAccount.{uid,password}（username = uid 的小写十六进制形式）
   ⚠ password 是 DES/ECB/PKCS5 加密后的十六进制串，不是明文！
     解密：DES-ECB decrypt(hex→bytes, key="SL2(M/eD" 的 8 字节 ASCII) → PKCS5 去填充 → 明文
     （参考 ChaoxingSignFaker ChaoxingIMHelper.resolveImPassword；v3.1 内置纯 JS DES 实现，
       解密失败时回退按明文尝试）
2. 取 token（三种 body 形态依次尝试，不同网关部署接受的形态不同）：
   POST https://a1-vip6.easemob.com/cx-dev/cxstudy/token
        ① form:  grant_type=password&username=<u>&password=<p>
        ② JSON:  {"grant_type":"password","username":<u>,"password":<p>}（Content-Type: application/json）
        ③ JSON body + Content-Type: application/x-www-form-urlencoded（参考实现形态，代理 mode=3）
        UA: Easemob-SDK(Android) 4.9.0.1
3. 群列表：
   GET https://a1-vip6.easemob.com/cx-dev/cxstudy/users/<u>/joined_chatgroups?detail=true&version=v3&pagenum=1&pagesize=200
4. 群消息漫游：
   POST https://a1-vip6.easecdn.com/cx-dev/cxstudy/users/<u>/messageroaming
        body(JSON as text/plain): {"end":"-1","queue":"<gid>@conference.easemob.com","start":"-1"}
```

消息体 `msg` 为 base64 protobuf，嵌套结构：

```
Meta{ 4:timestamp, 6:MessageBody }
MessageBody{ 5:KeyValue{ 1:"attachment", 6:<JSON字符串> } }
JSON: { attachmentType:15, at_chat_course:{ atype:2|74, aid, title, courseInfo:{courseid,classid,coursename} } }
```

`attachmentType==15 && atype∈{2,74}` 即签到卡片。卡片直接给出 aid/courseId/classId：**非二维码类活动可立即自动签**；二维码类（atype=2）提交时仍需 enc —— 现场扫一次即可（enc 与账号无关，全班复用），App 为此提供「缓存 enc / 摄像头扫码 / 粘贴」三个入口。

## 8. 原生代理桥（本 App 私有协议）

H5 → 原生：

```
GET https://xxt.local/r?d=<urlencode(base64(JSON))>
JSON: { m:方法, u:目标URL, b:请求体, x:0json/1form/2multipart/3原样透传(不设Content-Type), h:自定义头, f:multipart字段, file:{name,filename,mime,b64} }
```

原生端点（v3.1 壳实现）：

| 端点 | 作用 |
|---|---|
| `/r?d=` | HTTP 代理（自动带 Cookie 罐） |
| `/cookies` | 读取 Cookie 罐（页面取 _uid/fid） |
| `/startmon?sec=N` `/stopmon` `/moninfo` `/monstat?t=` | 前台监控服务控制与状态回写 |

> 注：H5 还会调用 `/clearcookies`（登录前清罐）与 `/version`（关于页显示原生版本）。
> v3.1 原生壳尚未实现这两个端点，请求会静默失败（fetch 被 catch 吞掉，功能降级：
> 登录不清旧 Cookie、关于页显示「原生…」），计划在 v3.2 壳层补齐。

资源域：页面与静态资源挂在 `https://app.local/`（安全上下文，摄像头 API 可用）。
分享 scheme：`xxtqd://s?d=<base64url(JSON)>`，由原生 onNewIntent 注入页面 `window.__xxtShare(uri)`。

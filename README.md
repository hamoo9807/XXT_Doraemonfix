# Doraemon — 学习通智能签到助手

基于 [CofbroTeam/Doraemon](https://github.com/CofbroTeam/Doraemon) 二次开发，参考 [aquamarine5/ChaoxingSignFaker](https://github.com/aquamarine5/ChaoxingSignFaker) 架构。

> 更新记录见 [CHANGELOG.md](CHANGELOG.md)；安装包见仓库 Releases。

---

## 使用攻略

### 1. 登录

打开 App 后进入登录页，支持两种方式：

- **账号密码登录**: 输入学习通手机号 + 密码。密码经 AES 加密后本地存储，不会明文泄露。
- **手机验证码登录**: 输入手机号 → 获取验证码 → 支持粘贴 6 位验证码自动填表。

登录成功后 Cookie 自动保存，下次启动自动登录。

---

### 2. 绑定代签账号

> 个人中心 → 关联账号

输入待代签的学习通手机号 + 密码，点击绑定。App 会自动：
- 登录获取 Cookie
- 拉取真实姓名（而不是显示手机号）
- 密码 AES 加密存储

绑定后可在列表中长按删除，或通过 CheckBox 开关控制是否参与代签。

---

### 3. Cookie 导出 / 导入

> 个人中心 → 导出Cookie

点击导出时弹窗选择：

| 方式 | 内容 | 特点 |
|---|---|---|
| **完整导出** | Cookie + 加密密码 | 可自动续期，推荐 |
| **无密码导出** | 仅 Cookie | 约 5 天后失效，需重新导出 |

> 个人中心 → 导入Cookie

粘贴他人发来的 Cookie JSON，可填写手机号+密码支持自动重登。

---

### 4. 高德地图 Key（定位签到必备）

部分签到需要精确 GPS 定位。如果定位偏差大或失败：

1. 前往 [lbs.amap.com](https://lbs.amap.com) 注册并创建应用
2. 获取 **Web服务** API Key
3. 在 App 中：**个人中心 → 高德Key** 粘贴保存

⚠️ 不配置高德 Key 时，定位签到可能失败。

---

### 5. 预设地址

> 个人中心 → 预留地址

设置常用签到位置。签到时会优先尝试 GPS 定位，失败后自动遍历预设地址重试。

---

### 6. 代签设置

> 个人中心 → 代签所有绑定账号（开关）

开启后，主号签到成功会自动为所有已绑定的代签账号签到。代签过程：
- 独立 HTTP 客户端 + Cookie 隔离
- 已签到 / 非本班 → 静默跳过，不计失败
- Cookie 过期 → 自动用加密密码重新登录续期

---

### 7. 签到报告

> 首页 LOG 或 个人中心 → 签到报告

查看签到全过程日志（智能签到、课件签到、手势/签到码、定位、代签），可一键复制。纯内存收集，不影响签到性能。

---

## 签到类型支持

| 类型 | 说明 |
|---|---|
| 普通签到 | 直接提交 |
| 二维码签到 | 扫码后自动解析 enc 提交 |
| 手势签到 / 签到码 | 进课程活动列表，画出手势或输入签到码提交，输错可原地重试 |
| 定位签到 | GPS + 预设地址 + WGS84/BD09 坐标转换 |
| 拍照签到 | 进课程活动列表，从相册选图提交 |
| 人脸识别 | 无法自动完成，签到报告会提示 |

> 说明：需要人脸识别/拍照验证（防作弊）的签到不支持自动提交。

---

## 滑块验证码

自动弹窗滑动验证，支持：
- 图片自适应加载（URL / base64）
- 关闭按钮 + 返回键退出，不卡死
- 详细 debug 日志

---

## 项目结构

```
app/src/main/java/com/cofbro/qian/
  signer/      代签引擎 (ProxySignManager, ProxyUserClient, SignResult)
  home/        首页与课程/扫码智能签到 (HomeFragment)
  profile/     配置与账号管理 (ProfileFragment)
  account/     绑定账号 (AccountManagerActivity, AccountsAdapter, ImportOtherUserActivity)
  login/       登录 (LoginActivity, SMSActivity)
  wrapper/     课程活动列表 (WrapperActivity, TaskFragment: 普通/手势/签到码/定位/拍照)
  utils/       工具类 (NetworkUtils, CaptchaHelper, CacheUtils, CookieRefresher, DebugLogCollector, ...)
  view/        自定义View (GestureInputView, SliderCaptchaDialog, SignReportDialog, ...)
  data/        API URL 定义
```

---

## 构建

环境要求：

| 项 | 说明 |
|---|---|
| JDK | 17（AGP 8.1.0 + Kotlin 1.8.0） |
| Android SDK | compileSdk 33 / minSdk 24 / targetSdk 33 |
| Gradle | 8.5（wrapper 自带，无需另装） |

```bash
# 1. 写入本机 SDK 路径(该文件不入库)
echo "sdk.dir=<你的 Android SDK 路径>" > local.properties

# 2. 打包(Windows 用 gradlew.bat)
./gradlew :app:assembleDebug
```

产物与版本机制：

- 版本号存于根目录 `version.properties`，`versionCode = 主版本 * 10000 + 次版本`
- 每次构建输出到独立目录 `build/app_v<版本>/outputs/apk/...`，APK 命名为 `Doraemon-fix-v<版本>.apk`，并自动复制一份到根目录 `apk/`（不入库）
- 构建成功后次版本号自动 +1（失败不消耗版本号）；升主版本手动改 `version.properties`
- 高德 Key（可选）：在 `local.properties` 写入 `AMAP_API_KEY=xxx`，也可以在 App 内「个人中心 → 高德Key」配置；不配置时定位签到可能失败

---

## 免责声明

1. **仅供学习交流**: 本项目仅供学习 Android 开发、网络请求、自动化测试等技术用途，严禁用于任何商业目的或违反法律法规的行为。

2. **非官方产品**: 本项目与超星集团（北京世纪超星信息技术发展有限责任公司）及学习通平台无任何关联，未获得其授权或认可。

3. **用户责任**: 使用者应自行承担使用本项目所产生的一切后果，包括但不限于：
   - 违反学习通用户协议导致的账号封禁
   - 因代签行为被学校或教师发现的纪律处分
   - 任何因使用本项目造成的直接或间接损失

4. **不作保证**: 本项目按"现状"提供，不提供任何明示或暗示的保证，包括但不限于适销性、特定用途适用性。开发者不对本项目的可用性、准确性、可靠性承担责任。

5. **数据安全**: 本地存储的密码使用 AES 加密，Cookie 仅存储在应用私有目录。请勿将导出的 Cookie JSON 分享给不可信的第三方，否则可能导致账号被盗用。

6. **合规使用**: 请遵守当地法律法规及所在学校的规章制度。若您不同意以上条款，请立即停止使用并删除本项目。


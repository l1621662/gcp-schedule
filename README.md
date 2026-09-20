# JUWP Schedule（水贝贝）

江西水利电力大学的课表 Android App —— 水专也有自己的 App 了！

> **非学校官方应用**，学生自用学习项目，详见文末[免责声明](#免责声明)。

## 功能

**课表**
- 周 / 今日视图：11 小节网格、左右滑切换周次、时刻线、多课表
- 上课提醒、系统日历同步、调课规划（拆分 / 覆盖 / 交换）

**导入与备份**
- 教务登录一键导入（强智）：理论课表、实验课表、考试安排、成绩
- 课表 JSON 导入导出（字段对齐拾光课程表），备份含课程 + 成绩 + 学期配置 + 作息表

**成绩**
- 按学期 / 学年分组，加权平均分 / 平均绩点 / 修得学分，支持排序与排除任选课

**桌面小组件**
- 单条目拖大拖小自适应：2×2 看当前 / 下一节，4×2 多几节，4×4 及以上变本周课表；今天上完自动接明日

**校园生活**
- 胖乖生活：一键开水、余额查询、订单记录
- 共享单车扫码：输车号生成骑行二维码
- 校园卡付款码（默认关闭）：凭证加密存本机，可随时关闭
- 今日页快捷方式：自定义目标 App，可一键钉到桌面

## 使用

课表默认为空、不预置样例：

- 课表：**我的 → 教务导入**，登录教务导入学期课表
- 成绩：**我的 → 成绩查询**页内导入
- 考试：在教务导入 WebView 内打开考试安排页，一键导入

---

# 开发者

想复刻一个你自己学校的课表 App？整体架构、教务爬虫解析、WebView 导入实现与换校适配步骤，见 **[DEVELOPER.md](DEVELOPER.md)**。

## 环境要求

| 项 | 要求 |
|----|------|
| JDK | 17+ |
| Android Studio | Koala / Ladybug 及以上 |
| Android SDK | Platform 35，Build-Tools 35.x |
| Gradle / Kotlin | Wrapper 8.10.2 / 2.1.21（已随仓库配置） |
| minSdk / targetSdk | 26 / 35 |
| applicationId | `edu.jxslu.schedule`（debug 变体加 `.debug` 后缀，可与正式包共存） |

## 构建与测试

```bash
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

单测结果汇总在 `app/build/test-results/testDebugUnitTest/`。

## 工程结构

```
app/src/main/java/edu/jxslu/schedule/
  MainActivity.kt           # 底部导航：今日 / 课表 / 我的
  SubpageActivity.kt        # 二级页容器（课表管理/设置/成绩/调课/提醒/小组件等）
  JwImportActivity.kt       # 教务 WebView 导入（课表 / 考试 / 成绩）
  domain/                   # 纯逻辑层：Course / ScheduleCalculator 等，可 JVM 测
  data/local|repo|prefs|jw|qiekj|ykt/   # Room 存储 · 仓库 · DataStore · 教务解析 · 胖乖 · 一卡通
  ui/today|week|me|score|widget|…/      # Compose 界面按模块分包
scripts/                    # 教务爬虫（Python，本机调试用，见 scripts/README.md）
DESIGN.md                   # UI 与领域模型规格（改导航/课表模型前必读）
DEVELOPER.md                # 架构 + 爬取实现 + 换校适配指南
```

作息表（11 小节、每节 40 分钟）见 DESIGN §3.5；课表网格与 `Course.startSection/endSection` 均按小节号（1–11）。

## 发版

发版流程见 `.agents/skills/publish-release/SKILL.md`。对外 APK 必须用正式 keystore 签名；
`release.jks` / `keystore.properties` 不入库，缺失时构建回退 debug 签名（仅限本地调试）。

## 参考

- 拾光课程表：https://github.com/XingHeYuZhuan/shiguangschedule
- 胖乖参考实现：https://github.com/Inonvation/light-life
- HugeIcons Compose：`com.github.rikkahub:hugeicons-compose`（JitPack）

## 免责声明

本项目为学生自用学习项目，**非学校官方应用**，与江西水利电力大学无任何隶属或合作关系。

- 登录教务、调用胖乖生活与校园卡（一卡通）接口等行为均模拟正常客户端操作，存在账号与接口变更风险，使用后果自负。
- 应用不上传任何数据到第三方服务器；教务与校园卡凭证仅保存在本机（Android Keystore 加密存储，已排除云备份），凭证不写入代码仓库。
- 校园卡付款码等同现金：仅限本人使用，请勿截图或分享给他人，泄露可能被盗刷；若因启用相关功能造成损失，开发者概不负责。
- 严禁将本项目用于刷积分、绕过付费或任何伪造官方身份的用途。

## License

[MIT](LICENSE)

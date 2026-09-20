# JUWP Schedule（水贝贝）

水宝宝迎来了她的亲兄弟，水贝贝！水专也有自己的app了！

江西水利电力大学课表 Android App。

**非学校官方应用**，详见文末免责声明。

> **开发者请看**：想复刻一个你自己学校的课表 App？整体架构、教务爬虫脚本解析、
> App 端 WebView 导入实现与换校适配步骤，见 **[DEVELOPER.md](DEVELOPER.md)**。

## 你会得到什么

- 周课表 / 今日课表（11 小节网格、左右滑切换周次、时刻线、多课表）
- 教务系统登录导入（强智）：理论课表、实验课表、考试安排（历史学期按校历惯例估算开学日）
- 成绩查询：按学期 / 按学年分组，加权平均分 / 平均绩点 / 修得学分（默认不计任选课，可开关），支持按成绩 / 绩点排序
- 课表 JSON 导入导出（字段对齐拾光课程表；备份含课程 + 成绩 + 学期配置 + 作息表）
- 桌面小组件（单条目、拖大拖小自适应）：2×2 紧凑看正在上 / 下一节，4×2 多几节，
  4×4 及以上变本周课表（今天 / 明天列高亮）；今天上完自动接明日
- 上课提醒、系统日历同步、调课规划（拆分 / 覆盖 / 交换）
- 今日页快捷方式（自定义目标 App，支持一键钉到桌面）
- 胖乖生活：一键开水、余额查询、订单记录

## 环境要求

| 项 | 要求 |
|----|------|
| JDK | 17+ |
| Android Studio | Koala / Ladybug 及以上推荐 |
| Android SDK | Platform **35**，Build-Tools 35.x |
| Gradle | Wrapper 自带 **8.10.2** |
| Kotlin | **2.1.21** |
| minSdk / targetSdk | 26 / 35 |
| applicationId | `edu.jxslu.schedule`（debug 变体为 `.debug` 后缀，可与正式包共存） |
| 应用显示名 | 水贝贝（debug 变体显示「水贝贝 Debug」） |


## 工程结构

```
app/src/main/java/edu/jxslu/schedule/
  MainActivity.kt           # 底部导航：今日 / 课表 / 我的
  SubpageActivity.kt        # 二级页容器（课表管理/设置/成绩/调课/提醒/小组件等）
  JwImportActivity.kt       # 教务 WebView 导入（课表 / 考试 / 成绩）
  JuwApplication.kt         # ensureDefaults（节次/学期；课表不预置）+ 小组件冷启动刷新
  Graph.kt                  # 单例 Repository 装配
  domain/                   # Course / TimeSlot / SemesterConfig / ScheduleCalculator 等（纯逻辑，可 JVM 测）
  data/local/               # Room：courses / time_slots / semester_config / timetables / scores
  data/repo/                # ScheduleRepository + JSON 导入校验；ScoreRepository（成绩按学期替换）
  data/prefs/               # DataStore 全局偏好（显示 / 提醒 / 快捷方式 / 成绩口径）
  data/jw/                  # JwUrls + 强智理论课表 / 实验课表解析器
  data/qiekj/               # 胖乖生活 API（登录/开水/余额/订单）
  ui/today|week|me|score|widget|tweak|reminder|water|jwvw|timetable|common|theme/
scripts/                    # 教务爬虫（Python，本机调试用，见 scripts/README.md）
DEVELOPER.md                # 开发者文档：架构 + 爬取实现 + 换校适配指南
DESIGN.md · PROMPTS.md · AGENTS.md · LICENSE
```

课表默认为空，不预置样例：在「我的 → 教务导入」登录教务导入学期课表；
成绩在「我的 → 成绩查询」页内导入；考试安排在教务导入 WebView 内打开考试安排页后一键导入（自动识别页面）。

作息表（11 小节、每节 40 分钟）见 DESIGN §3.5；课表按**小节**建网格，`startSection/endSection` 就是小节号。

单元测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

## 发版

发版流程见 `.agents/skills/publish-release/SKILL.md`。对外产出的 APK 使用正式 keystore 签名，
`release.jks` 与 `keystore.properties` 不入库；缺失时构建自动回退 debug 签名（仅供本地调试）。

## 免责声明

本项目为学生自用学习项目，**非学校官方应用**，与江西水利电力大学无任何隶属或合作关系。

- 登录教务、调用胖乖生活接口等行为均模拟正常客户端操作，存在账号与接口变更风险，使用后果自负。
- 应用不上传任何数据到第三方服务器；账号密码仅由用户在 WebView 中手动输入，凭证不写入代码仓库。
- 严禁将本项目用于刷积分、绕过付费或任何伪造官方身份的用途。

## 参考

- 拾光课程表：https://github.com/XingHeYuZhuan/shiguangschedule
- 胖乖参考实现：https://github.com/Inonvation/light-life
- HugeIcons Compose：`com.github.rikkahub:hugeicons-compose`（JitPack）

## License

[MIT](LICENSE)

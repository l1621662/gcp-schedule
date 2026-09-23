# 城职课表（GCP Schedule）· Agent 工作规范

本文件给 AI Agent / 结对工具读。人看 `README.md` 与 `DESIGN.md`。

## 项目身份

- 名称：GCP Schedule / 显示名「城职课表」
- 学校：广州城市职业学院（**非官方**；对外文案必须带免责声明，现成文案见 `README.md`）
- 包名：`edu.gcp.schedule`（debug 变体加 `.debug` 后缀，详见「工程实况」）
- 形态：单模块 `:app` · Kotlin · Compose + Material3 · minSdk 26 / compileSdk 35

## 必读顺序

**按需读，不要通读**——这是公开仓库的上下文成本纪律：

1. 本文件（先读，看完就知道该去哪个文件找什么）
2. `PROMPTS.md` 只读你要做的那一个阶段块
3. `DESIGN.md` **只读与任务相关的章节**（§3 导航/UI 规格、§4.1–4.8 结构规格、§3.5 作息表）；
   历史实现记录在 `docs/devlog.md`（仅本地），只在排查"当初为什么这么改"时才翻
4. 只读参考：`F:\light-life-v3.0`（原项目的胖乖实现，可选）；教务相关实现全部在 App 内（见 DESIGN §4.4/§4.17）

改导航或课表领域模型前，必须先改 `DESIGN.md` 对应章节（不是 devlog）。

## 工程实况（勿按过时文档猜）

| 项 | 现值 |
|----|------|
| Gradle | Wrapper **8.10.2** |
| AGP | **8.7.3** |
| Kotlin | **2.1.21**（+ compose / serialization / KSP 同版本） |
| Room | **2.7.1**（2.6 + Kotlin 2.1 会 KSP `unexpected jvm signature V`） |
| Room DB | **v6**：v2 加 `courses.kind`（理论/实验），v3 加多课表（`timetables` 表 + `courses.timetableId`），v4 加成绩表 `scores`，v5 加调课检测（`detect_baselines`/`detect_reports`），v6 加一卡通流水（`ykt_turnovers`，**2026-09-23 该功能已移除，表与迁移保留、无实体**）。逐级 `ALTER TABLE`/`CREATE TABLE`，**禁止**改 destructive |
| 作息表 | **11 小节**（每节 40 分钟，大节内 5 分钟；跨大节：1-2→3-4 为 20 分钟、5-6→7-8 为 15 分钟、3-4→5-6 午休 140、7-8→9-11 晚饭 85）。2026-09-23 起 7–11 节为本校实际作息，schema v2，见 DESIGN 3.5 |
| 课表网格 | 行号 = **小节号 1–11**（不是大节号）；`Course.startSection/endSection` 也是小节号 |
| HugeIcons | `com.github.rikkahub:hugeicons-compose:1.4`（**JitPack**，**`isTransitive = false`**） |
| Glance | `androidx.glance:glance-appwidget:1.2.0`（桌面小组件，单条目 `SizeMode.Exact`）；传递抬 compose runtime 至 1.7.8，`androidx.core` 仍 1.15.0 |
| 图标用法 | `import me.rerere.hugeicons.stroke.*` + `HugeIcons.Calendar01` 等 |
| 样例课 | **已移除**；课表默认空，从教务 WebView 导入 |
| 包名 | release = `edu.gcp.schedule`；debug 加后缀 = `edu.gcp.schedule.debug`（两者签名不同，**必须**靠后缀区分，否则互相覆盖安装） |

HugeIcons **不要**写 `me.rerere:hugeicons-compose:1.0.0`（Maven Central 不存在）。不要打开其传递依赖（会拉 `androidx.core` 1.17，AGP 8.7/compileSdk 35 编不过）。

## 常用命令

```powershell
# 构建 + 测试（本机依赖已齐备，加 --offline 后秒级完成）
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline
# APK: app\build\outputs\apk\debug\app-debug.apk
```

- 测试结论从 `app/build/test-results/testDebugUnitTest/*.xml` 汇总（Gradle 成功时不打印用例数）；
  读 XML 用 `-Encoding UTF8`，否则中文断言消息乱码。
- 换机/重装后若 wrapper 重复下载：把 Gradle 8.10.2 解压版拷进
  `~/.gradle/wrapper/dists/gradle-8.10.2-bin/<distributionUrl 的 MD5-base36>/`，
  补一个空的 `gradle-8.10.2-bin.zip.ok`、删掉 `.part`。**不要**改 `distributionUrl`（哈希变则缓存对不上）。

单测覆盖：`ScheduleCalculatorTest`、`TimeSlotRulesTest`、`TimeSlotScheduleTest`（作息不变量）、
`WeekGridLayoutTest`（网格几何）、
`ImportJsonShapeTest`、`TodayStateTest`、`ParseWeeksInputTest`、
`CourseTweakTest`（调课规划：拆分/覆盖/交换/同格去重）、`TodayBoundaryTest`（小组件边界闹钟时刻）、
`WidgetModelTest`（小组件：尺寸分档/行数预算/**明日接棒**/周网格列序与去重叠/旧 JSON 兼容）、
`ExamMapperTest`（考试→课条目映射，含历史学期估算）、
`ExamScheduleParserTest`（考试 JSON 解析，强智口径待迁）、`ScoreCalculatorTest`（学期/学年汇总）、
`ScoreGroupsTest`（成绩学年分组与年级标签）、
`ShortcutsTest`（快捷方式：拉起口径/表单校验/预设表/JSON 兜底/列表操作）、
`ScheduleDetectTest`（调课检测三方合并：归因/冲突/调课不误报/序列化 roundtrip）、
`ZfJwSessionTest`（正方无界面登录：csrf/验证码标记解析、学期编码、会话 cookie 往返、IPv4 优先 DNS）、
`ZhengfangScheduleParserTest`（正方课表：周次单双周/节次范围/注入 JSON）、
`ZhengfangScoreParserTest`（正方成绩：分页脚本/字段映射/失败口径）
等 29 个测试类。

行为约定（改之前先读）：
- 正方课表页的星期/节次直接来自单元格 `td.td_wrap[id="<星期>-<节次>"]`，不要改回「按列序推」那套（旧强智算法已随解析器删除）。
- 课程配色不按课程名哈希取（12 桶内必然撞色），走 `ScheduleCalculator.colorIndexesBySortedName` / `nextColorIndex`。
- **学期字符串统一走 `domain/TermFormat.normalize`**：网页导入取到的是页面标题原文
  （`2026-2027学年第一学期`）、调课检测给的是规范形式（`2026-2027-1`）；两者比较前必须归一，
  否则会误报「教务已切换学期」把差异检测整条堵死（2026-09-23 真机 bug）。
- 作息表结构版本存在 DataStore（`DisplayPrefsStore.slotSchemaVersion`）；改作息要同时调 `DefaultData.SLOT_SCHEMA_VERSION` 并给迁移。
- 课程时间**只有一条口径**：`ScheduleCalculator.courseStartMinutes` / `courseEndMinutes`（自定义时间课以 custom 字段为准）。
  排序、行内时刻、倒计时、进度条、`coursePhase`/`nextCourse`/`dayLastEndMinutes` 全部走它，不要再自己写 `isCustomTime` 分支。
- 今日页结构：**焦点卡（正在上/下一节）+ 单时间轴**；焦点课从列表里剔除（`TodayUiState.listCourses`），
  同一节课不得两处出现；节次号只在焦点卡出现一次。改版前先读 DESIGN §3.3。
- 可见星期序列以 `ScheduleCalculator.visibleDays` 为唯一来源、`columnOf` 取列下标；
  不要用 `day - 1` 当列号（隐藏周六但显示周日时会错位）。
- 今日页底部固定区（只剩快捷方式网格，2026-09-23 起）是 `TodayBottomDock`，**钉在滚动区下方**、不进
  `LazyColumn`；三态（加载/空/有课）共用同一份，别只改一处。改版前先读 DESIGN §3.3。
- 一次性消息**只有一条通道**：页面 Scaffold 的 `snackbarHost = { AppSnackbarHost(snackbar) }`
  （`ui/common/AppNotice.kt`）。语气用 `NoticeTone` 四档，视觉规格见 DESIGN §3.2；
  **禁止**新增 `android.widget.Toast`（系统黑框，与 App 其余浮层两套观感）。
- `ModalBottomSheet` / `AlertDialog` 是**比页面高一层的独立窗口**：页面级提示在它打开时必然被盖住。
  提示要落在弹层里就用 `InlineNoticeRow`（或先关弹层再提示），**不要**指望 Snackbar 穿透；
  加弹层内的异步流程前先确认结果会显示在哪个窗口。
- 小组件是**单条目 + `SizeMode.Exact` 自适应**（2026-09-20 起，旧三档条目已删）：尺寸由
  `WidgetMetrics`（实测 dp）分档 Compact / List / Week，**不要再加按尺寸拆的 receiver 或
  `widget_info_*`**（旧版三条目内容重复，用户明确要求合并）。改渲染前先读 DESIGN §3.6；
  刷新机制（边界闹钟 + WorkManager + 冷启动）与「写状态 + `update()`」双步**不许动**
  （理由见 `ScheduleWidget` 类 KDoc：Glance 会话的两条硬约束是不可绕过的）。
- 小组件周网格的列取 `ScheduleCalculator.visibleDays`，与课表页同一口径（不要 `day - 1`）；
  高亮列规则是「今天还有课 → 今天，否则明天」，改这条前先读 DESIGN §3.6 的「明日接棒」。
- 澎湃OS / MIUI 的负一屏只收录「小米小部件」（需开放平台审核），**原生小组件进不去**；
  设置页已给出替代路径（负一屏搜索 / 日历同步），不要把它当 bug 修（DESIGN §3.6「负一屏」）。

## 装真机

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
# MIUI 可能弹「USB 安装」需在手机上允许
```

debug 与 release 是**两个独立应用**（包名分别 `edu.gcp.schedule.debug` / `edu.gcp.schedule`，
桌面名「城职课表 Debug」/「城职课表」），可同时安装、数据各一份。改动冲突时改 `app/src/debug/res/values/strings.xml`（仅覆盖 `app_name`）。

启动 debug 包**必须写全限定名**——短式 `am start -n <applicationId>/.MainActivity` 会按 applicationId
补前缀、解析成 `edu.gcp.schedule.debug.MainActivity` 并报 `Error type 3 ... does not exist`
（manifest 里声明的是源码包名，不含后缀）：

```powershell
adb shell am start -n edu.gcp.schedule.debug/edu.gcp.schedule.MainActivity
```

**设备列表看不到手机时**：先确认是不是根本没连。重跑 `adb connect` 无效、排除僵尸 adb /
小米妙享抢接口后，直接提醒用户插线或确认无线调试已开，不要在环境侧反复排查空转。

无线调试（手机重启或 `adb usb` 后失效，IP 要现取勿记死）：
`adb -s <serial> tcpip 5555` → `adb shell ip route` 取 IP（接口是 **wlan2**，不是 wlan0）→ `adb connect <ip>:5555`。

## 教务实现（全部在 App 内）

原项目的 Python 爬虫（`scripts/`，针对江西水利电力大学 · 强智教务）**已于 2026-09-23 整体删除** ——
它对本项目（正方教务）完全跑不通，且当时的「电脑抓取 → 导出 JSON → 手机导入」通道已被 App 内的
WebView 导入取代。真要重写一份 PC 端脚本，可从 git 历史（提交 `0059245` 之前）取回作参考，
但注意正方登录需要**人工输入验证码**，脚本化的边际价值不高。

当前两条实现链路（改之前先读）：

| 链路 | 位置 | 规格 |
|------|------|------|
| 课表/成绩导入（WebView + 注入 JS） | `data/jw/ZhengfangScheduleParser`、`ZhengfangScoreParser`、`ui/jwvw/JwImportScreen` | DESIGN §4.4 / §4.15 |
| 调课检测（无界面 HTTP） | `data/jw/ZfJwSession`（登录 + `kbList`）、`JwDetectRunner` | DESIGN §4.17 |

## 架构（改代码前对齐）

```
MainActivity → 底栏今日/课表/我的 + 路由 jw_import；SubpageActivity 承载二级页（含成绩查询 SCORES）
domain/          Course·TimeSlot·SemesterConfig·ScheduleCalculator·ExamMapper·Score（纯逻辑，可 JVM 测）
data/local/      Room v5：courses / time_slots / semester_config / timetables / scores
                 / detect_baselines / detect_reports（调课检测，DESIGN §4.17）
data/repo/       ScheduleRepository + JSON 导入校验；ScoreRepository（成绩按学期替换）
data/prefs/      DataStore 显示偏好（含 slotSchemaVersion）
data/jw/         JwUrls + ZhengfangScheduleParser（正方课表 DOM）+ ZhengfangScoreParser（正方成绩）
                 + ZfJwSession（正方无界面登录，调课检测用）+ ExamScheduleParser（考试，强智口径待迁）

ui/today|week|me|jwvw|score|timetable|detect|common|theme|widget
Graph.kt         单例 Repository
JuwApplication   ensureDefaults（节次/学期；课表不预置）+ 小组件冷启动刷新
```

- 课表 JSON 字段对齐 DESIGN 4.3 / 拾光互通；解析层全在 `data/jw/`
- 教务：**正方** `https://jwcjw.gcp.edu.cn`；登录 `/jwglxt/xtgl/login_slogin.html`（需验证码，图片接口 `/jwglxt/kaptcha?time=…`）→ 课表 `/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html`，数据接口 `/jwglxt/kbcx/xskbcx_cxKbxx.html`（DESIGN §4.4/§4.17）
- 解析 HTML 用注入 JS 抽 `li.courselists-item`；Kotlin 侧 `html.parser` 思路，**lxml 会丢节点**（双 doctype）

## 硬性禁止

- 禁止刷积分、绕过付费、伪造官方身份
- 禁止把学号/密码/token 写进 git（`local.properties`、`release.jks`、`keystore.properties` 已 ignore）
- **本仓库为公开仓库**：写文档/注释/示例时不得出现真实学号、姓名、手机号、token；新增抓取产物目录前先确认 `.gitignore` 已覆盖
- 禁止未改 `DESIGN.md` 就改导航或课表领域模型
- 禁止 emoji 当功能图标；HugeIcons 查名用本地 JAR，勿猜
- 禁止把「能编译」当完成；禁止故意压制编译错误

## 已移除的第三方模块（2026-09-23）

- **胖乖生活**（一键开水/余额/订单，原 `data/qiekj/` + `ui/water/`）
- **水宝宝一卡通**（余额/付款码/微信直充/流水，原 `data/ykt/` + `ui/campus/`）
- **快趣共享单车出码**（原 `ui/ebike/` + `domain/EbikeQr.kt`）

都是原学校（江西水利电力大学）周边的第三方服务，与当前学校无关，代码与入口已整体删除；
数据库表 `ykt_turnovers` 保留在 v6 迁移中（空表、无实体），桌面备份规则不再排除其凭证文件。
如需恢复，请从 git 历史（提交 3552f02 之前）取回，并同步 DESIGN §3.4/§3.9/§3.10。

## 沟通与 DoD

- 与用户中文交流；少形容词，多可验证结论
- 完成定义：功能可演示（真机/模拟器或写明阻塞）；能跑则跑 `assembleDebug` + `testDebugUnitTest`；未越权改无关模块

## 阶段状态（见 DESIGN.md §6 里程碑）

P1 脚手架 · P2 Room+UI · P3 我的页导入导出/学期 · P4 胖乖（2026-09-23 随学校切换移除）·
P5 教务 WebView（正方）— **已完成并真机验证** · P5b 实验课表导入 — **已移除**（正方课表含实验课，见 DESIGN §4.8）  
P6 打磨 — **进行中**

## 仓库与发版

- 原项目：https://github.com/Inonvation/JUWP-Schedule （MIT）；本仓库是基于它的「广州城市职业学院 · 正方教务」改版
- **不入库**（已 gitignore，本地保留）：`docs/`、`release.jks`、`keystore.properties`
- 发版流程见 `.agents/skills/publish-release/SKILL.md`；图标查名见 `.agents/skills/find-hugeicons/SKILL.md`
- 对外发版必须用正式 keystore 签名；`release.jks` 缺失时构建回退 debug 签名（**仅本地调试**，不可对外分发）

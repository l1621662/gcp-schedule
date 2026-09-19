# JUWP Schedule · Agent 工作规范

本文件给 AI Agent / 结对工具读。人看 `README.md` 与 `DESIGN.md`。

## 项目身份

- 名称：JUWP Schedule / 显示名「水贝贝」
- 学校：江西水利电力大学（**非官方**；对外文案必须免责声明）
- 包名：`edu.jxslu.schedule`（debug 变体加 `.debug` 后缀，详见「工程实况」）
- 形态：单模块 `:app` · Kotlin · Compose + Material3 · minSdk 26 / compileSdk 35

## 必读顺序

**按需读，不要通读**——这是公开仓库的上下文成本纪律：

1. 本文件（先读，看完就知道该去哪个文件找什么）
2. `PROMPTS.md` 只读你要做的那一个阶段块
3. `DESIGN.md` **只读与任务相关的章节**（§3 导航/UI 规格、§4.1–4.8 结构规格、§3.5 作息表）；
   历史实现记录在 `docs/devlog.md`（仅本地），只在排查"当初为什么这么改"时才翻
4. 只读参考：`F:\light-life-v3.0`（胖乖）、`scripts/`（教务爬虫，说明见 `scripts/README.md`）

改导航或课表领域模型前，必须先改 `DESIGN.md` 对应章节（不是 devlog）。

## 工程实况（勿按过时文档猜）

| 项 | 现值 |
|----|------|
| Gradle | Wrapper **8.10.2** |
| AGP | **8.7.3** |
| Kotlin | **2.1.21**（+ compose / serialization / KSP 同版本） |
| Room | **2.7.1**（2.6 + Kotlin 2.1 会 KSP `unexpected jvm signature V`） |
| Room DB | **v5**：v2 加 `courses.kind`（理论/实验），v3 加多课表（`timetables` 表 + `courses.timetableId`），v4 加成绩表 `scores`，v5 加调课检测（`detect_baselines`/`detect_reports`）。逐级 `ALTER TABLE`/`CREATE TABLE`，**禁止**改 destructive |
| 作息表 | **11 小节**（每节 40 分钟，大节内 5 分钟、大节之间 20 分钟换教室），见 DESIGN 3.5 |
| 课表网格 | 行号 = **小节号 1–11**（不是大节号）；`Course.startSection/endSection` 也是小节号 |
| HugeIcons | `com.github.rikkahub:hugeicons-compose:1.4`（**JitPack**，**`isTransitive = false`**） |
| Glance | `androidx.glance:glance-appwidget:1.2.0`（桌面小组件）；传递抬 compose runtime 至 1.7.8，`androidx.core` 仍 1.15.0 |
| 图标用法 | `import me.rerere.hugeicons.stroke.*` + `HugeIcons.Calendar01` 等 |
| 样例课 | **已移除**；课表默认空，从教务 WebView 导入 |
| 包名 | release = `edu.jxslu.schedule`；debug 加后缀 = `edu.jxslu.schedule.debug`（两者签名不同，**必须**靠后缀区分，否则互相覆盖安装） |

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
`WeekGridLayoutTest`（网格几何）、`QiangzhiScheduleParserTest`、`SyjxScheduleParserTest`、
`ImportJsonShapeTest`、`TodayStateTest`、`ParseWeeksInputTest`、`QiekjSignTest`、
`CourseTweakTest`（调课规划：拆分/覆盖/交换/同格去重）、`TodayBoundaryTest`（小组件边界闹钟时刻）、
`WidgetModelTest`（小组件快照口径与尺寸裁剪）、`ExamMapperTest`（考试→课条目映射，含历史学期估算）、
`ExamScheduleParserTest` / `ScoreParserTest`（注入 fetch JSON 解析）、`ScoreCalculatorTest`（学期/学年汇总）、
`ScoreGroupsTest`（成绩学年分组与年级标签）、
`ShortcutsTest`（快捷方式：拉起口径/表单校验/预设表/JSON 兜底/列表操作）、
`ScheduleDetectTest`（调课检测三方合并：归因/冲突/调课不误报/序列化 roundtrip）、
`JwHttpSessionTest`（检测登录链路：重定向解析参数顺序、IPv4 优先 DNS）
等 32 个测试类。

行为约定（改之前先读）：
- 教务页星期只能从课程所在 `<td>` 的**列序**推（第 0 列是节次标签）。`li.qz-hasCourse-N` 恒为 1，不能当星期来源。
- 课程配色不按课程名哈希取（12 桶内必然撞色），走 `ScheduleCalculator.colorIndexesBySortedName` / `nextColorIndex`。
- 作息表结构版本存在 DataStore（`DisplayPrefsStore.slotSchemaVersion`）；改作息要同时调 `DefaultData.SLOT_SCHEMA_VERSION` 并给迁移。
- 课程时间**只有一条口径**：`ScheduleCalculator.courseStartMinutes` / `courseEndMinutes`（自定义时间课以 custom 字段为准）。
  排序、行内时刻、倒计时、进度条、`coursePhase`/`nextCourse`/`dayLastEndMinutes` 全部走它，不要再自己写 `isCustomTime` 分支。
- 今日页结构：**焦点卡（正在上/下一节）+ 单时间轴**；焦点课从列表里剔除（`TodayUiState.listCourses`），
  同一节课不得两处出现；节次号只在焦点卡出现一次。改版前先读 DESIGN §3.3。
- 可见星期序列以 `ScheduleCalculator.visibleDays` 为唯一来源、`columnOf` 取列下标；
  不要用 `day - 1` 当列号（隐藏周六但显示周日时会错位）。

## 装真机

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
# MIUI 可能弹「USB 安装」需在手机上允许
```

debug 与 release 是**两个独立应用**（包名分别 `edu.jxslu.schedule.debug` / `edu.jxslu.schedule`，
桌面名「水贝贝 Debug」/「水贝贝」），可同时安装、数据各一份。改动冲突时改 `app/src/debug/res/values/strings.xml`（仅覆盖 `app_name`）。

启动 debug 包**必须写全限定名**——短式 `am start -n <applicationId>/.MainActivity` 会按 applicationId
补前缀、解析成 `edu.jxslu.schedule.debug.MainActivity` 并报 `Error type 3 ... does not exist`
（manifest 里声明的是源码包名，不含后缀）：

```powershell
adb shell am start -n edu.jxslu.schedule.debug/edu.jxslu.schedule.MainActivity
```

**设备列表看不到手机时**：先确认是不是根本没连。重跑 `adb connect` 无效、排除僵尸 adb /
小米妙享抢接口后，直接提醒用户插线或确认无线调试已开，不要在环境侧反复排查空转。

无线调试（手机重启或 `adb usb` 后失效，IP 要现取勿记死）：
`adb -s <serial> tcpip 5555` → `adb shell ip route` 取 IP（接口是 **wlan2**，不是 wlan0）→ `adb connect <ip>:5555`。

## 教务爬虫（scripts/）

正式脚本 5 个；历史一次性探测脚本在 `scripts/_archive/`（**勿依赖**，仅留档；该目录不入公开仓库）。

| 文件 | 作用 | 产出 |
|------|------|------|
| `jw_session.py` | 共享登录（CAS → 教务 SSO → 会话校验） | — |
| `fetch_courses.py` | 学期理论课表（`--term` 可选） | `scripts/out/courses.json` |
| `fetch_lab_courses.py` | 实验课表（实践实验 → 实验课表查询，`--term` 可选） | `scripts/out/lab_courses.json` |
| `fetch_exams.py` | 考试安排（`--term` 可选，缺省取教务当前学期；JSON 接口） | `scripts/out/exams.json` |
| `fetch_scores.py` | 课程成绩（`--term` 可选，缺省全部学期；JSON 接口） | `scripts/out/scores.json` |

所有脚本输出 JSON 顶层 `term` = **实际爬到的学期**（如 `2026-2027-1`），App 导入确认弹窗据此展示；
带 `--term` 时脚本会校验「请求学期 = 教务返回学期」，不一致直接报错而不是静默爬错学期。

```powershell
.\.venv-scraper\Scripts\python.exe scripts\fetch_courses.py     # 可加 --term 2025-2026-2
.\.venv-scraper\Scripts\python.exe scripts\fetch_lab_courses.py
.\.venv-scraper\Scripts\python.exe scripts\fetch_exams.py
.\.venv-scraper\Scripts\python.exe scripts\fetch_scores.py
```

- **Session 必须 `trust_env = False`**：本机 shell 注入了 `HTTP_PROXY/HTTPS_PROXY`（IDE 本地代理），
  requests 默认走代理会让教务 SSO 落点返回 404、主页退回「用户没有登录」，现象像"教务挂了"。
  统一用 `jw_session.new_session()`，不要自己 `requests.Session()`。
- 凭证在 `scripts/credentials.local.json`（已 gitignore），禁止提交、禁止写进 App。
- 两个课表页结构**完全不同**：理论课表按课程所在 `<td>` 列序推星期；实验课表是「周次 × 节次」两级纵轴，
  周次挂在**行分组**上、且同门课按周拆成多块需聚合。解析逻辑**不可互相复用**。
- 考试安排/成绩走**不带 .do 的 layui JSON 接口**（`xsks/xsksap_list`、`kscj/cjcx_list`，参数
  `xnxqid`/`kksj` + 分页 `pageNum/pageSize`）；带 .do 的同名地址返回「系统功能暂未开放」no-open 页，
  不要把「功能被校方关闭」误判成「暂无数据」。学期参数：课表页 `xnxq01id`，考试 `xnxqid`，成绩 `kksj`。
- 登录链路、DOM 规则、排错表、WebView 注入 JS：**`scripts/README.md`**（比 DESIGN 更细）。

## 架构（改代码前对齐）

```
MainActivity → 底栏今日/课表/我的 + 路由 jw_import；SubpageActivity 承载二级页（含成绩查询 SCORES）
domain/          Course·TimeSlot·SemesterConfig·ScheduleCalculator·ExamMapper·Score（纯逻辑，可 JVM 测）
data/local/      Room v5：courses / time_slots / semester_config / timetables / scores
                 / detect_baselines / detect_reports（调课检测，DESIGN §4.17）
data/repo/       ScheduleRepository + JSON 导入校验；ScoreRepository（成绩按学期替换）
data/prefs/      DataStore 显示偏好（含 slotSchemaVersion）
data/jw/         JwUrls + QiangzhiScheduleParser（理论 xskb）+ SyjxScheduleParser（实验 syjx）
                 + ExamScheduleParser / ScoreParser（考试·成绩 = 同源 fetch JSON，非 DOM 解析）
data/qiekj/      胖乖生活 API（登录/开水/余额/订单）
ui/today|week|me|water|jwvw|score|timetable|common|theme|widget
Graph.kt         单例 Repository
JuwApplication   ensureDefaults（节次/学期；课表不预置）+ 小组件冷启动刷新
```

- 课表 JSON 字段对齐 DESIGN 4.3 / 拾光互通；解析层见 `scripts/fetch_courses.py` 与 `data/jw/`
- 教务：强智 `https://jiaowu.juwp.edu.cn:81/` → SSO service 必须是 `http://jiaowu.juwp.edu.cn/sso.jsp`（**不要带 :81/:8080**）→ 课表 `http://jiaowu.juwp.edu.cn:8080/jsxsd/xskb/xskb_list.do?viweType=0`
- 解析 HTML 用注入 JS 抽 `li.courselists-item`；Kotlin 侧 `html.parser` 思路，**lxml 会丢节点**（双 doctype）

## 硬性禁止

- 禁止刷积分、绕过付费、伪造官方身份
- 禁止把学号/密码/token 写进 git（`scripts/credentials.local.json`、`local.properties`、`release.jks`、`keystore.properties` 已 ignore）
- **本仓库为公开仓库**：写文档/注释/示例时不得出现真实学号、姓名、手机号、token；新增抓取产物目录前先确认 `.gitignore` 已覆盖
- 禁止未改 `DESIGN.md` 就改导航或课表领域模型
- 禁止 emoji 当功能图标；HugeIcons 查名用本地 JAR，勿猜
- 禁止把「能编译」当完成；禁止故意压制编译错误

## 胖乖（P4）

- 参考 `F:\light-life-v3.0`；Base `https://userapi.qiekj.com/`
- 只做：登录、开水、余额、订单；签到默认关；禁止刷积分
- 实现在 `data/qiekj/` + `ui/water/`；Token 走 EncryptedSharedPreferences，禁止进日志
  调用链（11 步顺序不可乱）见 DESIGN §4.10

## 沟通与 DoD

- 与用户中文交流；少形容词，多可验证结论
- 完成定义：功能可演示（真机/模拟器或写明阻塞）；能跑则跑 `assembleDebug` + `testDebugUnitTest`；未越权改无关模块

## 阶段状态（见 DESIGN.md §6 里程碑）

P1 脚手架 · P2 Room+UI · P3 我的页导入导出/学期 · P4 胖乖（已实现，待真机验证）·
P5 教务 WebView · P5b 实验课表导入 — **已完成**  
P6 打磨 — **进行中**

## 仓库与发版

- 公开仓库：https://github.com/Inonvation/JUWP-Schedule （MIT）
- **不入库**（已 gitignore，本地保留）：`scripts/out/`（含真实学号/姓名/会话）、
  `scripts/_archive/`、`docs/`、`scripts/gen_week_layout_preview.py`、`release.jks`、`keystore.properties`
- 发版流程见 `.agents/skills/publish-release/SKILL.md`；图标查名见 `.agents/skills/find-hugeicons/SKILL.md`
- 对外发版必须用正式 keystore 签名；`release.jks` 缺失时构建回退 debug 签名（**仅本地调试**，不可对外分发）

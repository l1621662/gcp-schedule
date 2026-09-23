# 开发者文档：复刻一个你自己学校的课表 App

本文档面向想把这套方案搬到**自己学校**的开发者：讲清楚本项目的整体架构、
「从教务系统拿到课表/考试/成绩」两条完整数据链路的实现细节，以及换校适配的动手步骤。
应用功能与界面规格见 [DESIGN.md](DESIGN.md)。

> ⚠️ **部分内容已过时**：本文写作时针对的是原项目（江西水利电力大学 · 强智教务），
> 其中的 Python 爬虫（`scripts/`）与 CAS / `jiaowu.juwp.edu.cn` 相关段落**已随 2026-09-23 的迁移删除**，
> 仅作「换校适配思路」的历史参考。本项目当前实现（正方教务）以 `AGENTS.md` 与 `DESIGN.md` 为准。

> 本项目是**广州城市职业学院（正方教务）的非官方学生项目**，仅供学习交流。
> 换校适配时请同样遵守：模拟正常客户端操作、凭证不入代码仓库、不刷积分、不伪造官方身份。

---

## 目录

1. [全景：两条数据链路](#1-全景两条数据链路)
2. [工程形态与架构分层](#2-工程形态与架构分层)
3. [领域模型与数据存储](#3-领域模型与数据存储)
4. [链路 A：Python 爬虫（**已删除**，仅存历史参考）](#4-链路-apython-爬虫逆向参考实现)
5. [链路 B：App 端 WebView 导入（生产路径）](#5-链路-bapp-端-webview-导入生产路径)
6. [核心算法与口径（改代码前必读）](#6-核心算法与口径改代码前必读)
7. [考试安排映射](#7-考试安排映射)
8. [换一所学校：适配指南](#8-换一所学校适配指南)
9. [测试](#9-测试)
10. [构建环境与已知坑](#10-构建环境与已知坑)
11. [安全与合规红线](#11-安全与合规红线)

---

## 1. 全景：两条数据链路

本项目要解决的核心问题只有一个：**把教务系统里的课表，变成手机上可交互的数据**。
围绕它有两条互补的链路：

```
链路 B（App 端导入 —— 生产路径，用户数据不出本机）
  JwImportActivity → WebView 里用户自己登录正方教务（登录需人工输入验证码）
    → 按当前 URL 判定页面类型 → 注入 JS 抽 DOM / 同源 fetch JSON
    → Kotlin 解析成 Course / ScoreRecord → 确认弹窗（选目标课表/合并或覆盖）→ Room 入库

链路 C（调课检测 —— 无界面 HTTP，仅本机）
  ZfJwSession：登录页拿 csrftoken + /jwglxt/kaptcha 取验证码图 → 提交 → kbList JSON
    手动验证一次后会话 cookie 加密留存，后台定时检测复用它（过期则提示再验证）
```

> **链路 A（Python 爬虫）已于 2026-09-23 删除**：它跑在开发机上（原项目用来逆向页面结构、
> 产出 fixture 快照、以及「电脑抓取 → 导 JSON → 手机导入」）。正方登录需要人工验证码，
> 脚本化的价值不高，App 内的导入已覆盖这条需求。下表仍保留其位置说明，仅作历史参考。

**App 端不内置任何账号密码**：用户在 WebView / 设置页自己登录，凭证只留在本机
（导入走系统 `CookieManager`；调课检测走 Android Keystore 加密存储），这是隐私与合规的底线。

---

## 2. 工程形态与架构分层

| 项 | 值 |
|----|-----|
| 模块 | 单模块 `:app` |
| 语言/UI | Kotlin 2.1.21 + Jetpack Compose + Material3 |
| 持久化 | Room 2.7.1（课表/成绩）+ DataStore（显示偏好） |
| 网络 | App 端 = WebView（导入）+ OkHttp（调课检测的无界面登录与课表抓取）；零自建后端 |
| SDK | minSdk 26 / compileSdk 35 |
| 测试 | 纯 JVM 单测约 30 个类（domain 层可全量测，见 §9） |

分层与依赖方向（`app/src/main/java/edu/gcp/schedule/`）：

```
MainActivity.kt        底栏三 Tab：今日 / 课表 / 我的
SubpageActivity.kt     二级页容器（成绩查询、各类设置）
JwImportActivity.kt    教务导入独立窗口（独立 Activity，见 §5）
Graph.kt               手写单例装配：Repository / 数据库 / 偏好
domain/                纯 Kotlin：Course、ScheduleCalculator、ExamMapper、
                       ScheduleExporter、Shortcuts …… 不依赖 Android，可 JVM 测
data/local/            Room：Entities / Daos / JuwDatabase（含逐级迁移）
data/repo/             ScheduleRepository（课表读写 + 导入校验）、ScoreRepository
data/prefs/            DataStore 显示偏好（全局一份，不挂课表）
data/jw/               教务导入：JwUrls、两个课表解析器、考试/成绩解析器
data/qiekj/            胖乖生活 API（登录/开水/余额/订单）
ui/                    Compose Screen + ViewModel（today/week/me/water/score/...）
ui/widget/             Glance 桌面小组件
```

原则：

- UI 不直接碰网络与数据库，一律走 Repository；
- 教务解析结果必须先变成 `domain.Course` 才能入库（解析层只产出纯模型）；
- 所有可能出错的外部交互（导入、网络、解析）都要有可展示的错误文案与重试路径。

---

## 3. 领域模型与数据存储

### 3.1 三个核心模型（`domain/Models.kt`）

```kotlin
data class Course(
    val id: Long,
    val name: String, val teacher: String, val position: String,
    val day: Int,              // 1=周一 … 7=周日
    val startSection: Int,     // 小节号 1–11（不是大节号！）
    val endSection: Int,
    val weeks: Set<Int>,       // 教学周
    val isCustomTime: Boolean = false,       // 自定义时间课（考试也走这里）
    val customStartTime: String? = null,     // "HH:mm"
    val customEndTime: String? = null,
    val colorIndex: Int = 0,
    val kind: CourseKind = CourseKind.Theory, // Theory / Lab / Exam
)

data class TimeSlot(val number: Int, val startTime: String, val endTime: String) // 小节 1–11

data class SemesterConfig(
    val startDate: String,     // 第 1 周周一，"yyyy-MM-dd"
    val totalWeeks: Int = 20,
    val firstDayOfWeek: Int = 1,
)
```

两个关键设计决策：

1. **`kind` 扩展而非新表**。实验课、考试都复用 `Course`：
   考试日期落 `weeks`（单元素）+ `day`，起止时刻落 `customStart/EndTime`
   （`isCustomTime = true`，今日页倒计时/时刻口径自动生效），考场落 `position`。
   加一种课程类型**不需要任何数据库迁移**。
2. **行号 = 小节号**。本校作息是 11 小节、每节 40 分钟（大节内歇 5 分钟、
   大节之间 20 分钟换教室）。网格第 N 行就是第 N 小节，与教务返回的
   `startSection/endSection` 直接对齐，不做任何折算。详见 DESIGN §3.5。

### 3.2 Room schema（`data/local/JuwDatabase.kt`，当前 v4）

| 表 | 主键 | 说明 |
|----|------|------|
| `timetables` | `id` | 课表身份；`slotsCustomized` 标记用户改过作息 |
| `courses` | `id` | `timetableId` 外挂归属；`weeksCsv` 存逗号分隔周次；`kind` 列区分课型 |
| `time_slots` | `(timetableId, number)` | 每张课表一份作息表 |
| `semester_config` | `timetableId` | 每张课表一份开学日/总周数 |
| `scores` | `id` + `term` 索引 | 成绩全局归属学生、不挂课表；按学期整体替换 |

迁移纪律：**逐级 `ALTER TABLE` / `CREATE TABLE`，禁用 destructive migration**。
用户设备上是真实课表，重建表式的迁移等于删库。主键变更（v2→v3 把作息表从全局单份
改为每课表一份）只能「建新表 → 搬数据 → 改名」。

### 3.3 JSON 导入导出（与拾光课程表互通）

`ScheduleRepository.exportJson()/importJson()` 产出/读取的顶层结构：

```json
{
  "courses": [ { "name": "...", "teacher": "...", "position": "...",
                 "day": 1, "startSection": 1, "endSection": 2,
                 "weeks": [1,2], "colorIndex": 0, "kind": "theory" } ],
  "scores":  [ { "term": "2025-2026-2", "name": "...", "scoreStr": "92", ... } ]
}
```

- 字段名与领域模型一致，第三方课表 App（拾光）的用户可以互导；
- `scores` 段可选：旧版 App 用 `ignoreUnknownKeys` 忽略，新版读旧文件缺省为空，双向兼容；
- 课程导入支持「合并（按 mergeKey 去重）/覆盖」，成绩导入是**按学期整体替换**（先清后插）。

---

## 4. 链路 A：Python 爬虫（**已删除**）

> 本节描述的实现（`scripts/jw_session.py`、`fetch_courses.py`、`fetch_lab_courses.py`、
> `fetch_exams.py`、`fetch_scores.py`）已于 2026-09-23 整体删除，原因见 §1 的说明。
> 下面的内容仅作「如何逆向一个教务系统」的思路参考，命令与路径都已不可用；
> 需要取回代码可从 git 历史（提交 `0059245` 之前）恢复。
这一节讲**可迁移的方法论**：即使你的学校不是强智教务，排查套路也是同一套。

### 4.1 登录链路（`jw_session.py`）

本校链路：CAS 统一认证 → 教务 SSO，共三步，每步都要显式校验：

```
[1] CAS：GET 门户落地页拿登录页 → POST username/password/execution/_eventId=submit
    → 302 后 session 里有了 TGC cookie
[2] SSO：先预热教务 :81 端口拿 bzb_njw cookie（缺了教务不认票据，这是「第一次必挂」的根因）
    → GET cas/login?service=http://jiaowu.juwp.edu.cn/sso.jsp   ← service 不能带端口，带了 500
    → 跟 302 链落到教务学生端 xsMainV.htmlx
[3] 校验：请求主页，确认不是「用户没有登录」的 860 字节退化响应（正常主页 ~150KB）
```

三条通用经验：

1. **`trust_env = False`**：开发机 shell 里被注入的 `HTTP_PROXY/HTTPS_PROXY` 会被
   `requests` 默认读取，而很多教务对代理出口区别对待，现象是「同一请求时而 200 时而
   404」，极易误判成教务挂了。所有 Session 显式直连。
2. **手动跟 302 而不是 `allow_redirects=True`**：保留每一跳落点，出问题能说出断在哪。
3. **每个环节都有可判定的成功标志**（cookie 名、落点 URL、主页字节数/标记文本），
   失败时报「断在哪一步」而不是笼统的「登录失败」。

### 4.2 理论课表解析（`fetch_courses.py` + `data/jw/QiangzhiScheduleParser.kt`）

页面：`GET /jsxsd/xskb/xskb_list.do?viweType=0`（学期参数 `xnxq01id`）。
课程块是 `li.courselists-item`，名称在 `.qz-hasCourse-title`，
详情在 `.qz-hasCourse-abbrinfo`（形如 `老师:张三;时间:1-10周[1-2节];地点:XX楼(B102)`）。

**星期必须从课程所在 `<td>` 的列序推**——这是本项目踩过最深的坑，规则值得抄走：

- 表格第 0 列是节次标签，第 1–7 列才是周一到周日；
- `li` 上的 `qz-hasCourse-N` class **恒为 1**（模板拿它当「有课」样式），不能当星期来源；
- 列号要**累加 `colspan`**，并用一张 carry 表记录 `rowspan` 的跨行占用：
  强智在「同一天连续两大节上同一门课」时会合并单元格，不补偏移的话，
  该行之后所有课程的星期会整体前移一格。

核心算法（Python 与 Kotlin 注入 JS 是同一套，`fetch_courses.py:parse_courses`）：

```python
carry: dict[int, int] = {}          # 列号 → 还剩几行被上方 rowspan 占用
for tr in soup.select("tbody tr"):
    for key in list(carry):         # 每行开始先衰减占用
        carry[key] -= 1
        if carry[key] <= 0: del carry[key]
    col = 0
    for td in tr.find_all("td", recursive=False):
        while carry.get(col, 0) > 0: col += 1    # 跳过被上方合并占掉的列
        rowspan = int(td.get("rowspan") or 1)
        colspan = int(td.get("colspan") or 1)
        if rowspan > 1: carry[col] = rowspan
        if td.get("name") == "kbDataTd" and 1 <= col <= 7:
            ...           # 该格里的每个 li 就是一门课，day = col
        col += colspan
```

配套细节：

- 详情文本用正则拆 `老师/时间/地点`；周次串 `1-10`、`1,3,5-8` 展开成集合（限定 1..40）；
- 学期口径取 `select#xnxq01id` 的 selected 项——教务**会忽略未知学期参数**照常返回当前
  学期，所以脚本校验「请求学期 = 返回学期」，不一致直接报错而不是静默爬错学期；
- 用 `html.parser` 而不是 `lxml`：强智页面有双 doctype，lxml 会丢节点。

### 4.3 实验课表解析（`fetch_lab_courses.py` + `data/jw/SyjxScheduleParser.kt`）

页面：`GET /jsxsd/syjx/toXskb.do`。与理论课表**没有任何可复用之处**——
同一教务里两张课表页结构完全不同，解析逻辑不可互相套用。差异有三：

1. 没有 `td[name=kbDataTd]`，也没有 `老师:X;时间:Y;地点:Z` 合并文本；
   `abbrinfo` 里只有地点，**页面根本不提供教师字段**（保持空串而不是瞎猜）。
2. 表是「周次 × 节次」两级纵轴：每个周次占 6 行，周次标签是首行里带 `rowspan=6`
   的单元格。**课块不知道自己属于哪一周**，必须按行向上找周次标签。
3. 同一门课在每个有课周次各出一块，必须聚合：按
   `(名称, 星期, 起止节次, 地点)` 分组、`weeks` 取并集。
   聚合键**必须含地点**——实训课按批次分周上课，同名课会在不同实训室
   （实测一门课分布在三个房间），只按课名合并会丢掉地点差异。

星期换算公式（首行比其他行多一列周次标签，按行形态右对齐）：

```
day = td 下标 - (本行 td 总数 - 8)      # 8 = 节次标签 + 7 天
```

另：tooltip 里的「节次：60304」是页面内部编码，不是真实节次，只能取行标签。

### 4.4 考试与成绩（`fetch_exams.py` / `fetch_scores.py` + App 同名解析器）

两者都是 **layui 表格的 JSON 接口**，GET 即可，不解析 HTML：

```
考试安排  GET /jsxsd/xsks/xsksap_list?xnxqid=<学期>&xqlb=&pageNum=1&pageSize=200
课程成绩  GET /jsxsd/kscj/cjcx_list?kksj=<学期|空=全部>&kcxz=&kcsx=&kcmc=&xsfs=&pageNum=1&pageSize=200
响应      { "code": 0, "count": <总条数>, "data": [ ... ] }
```

三个必踩坑（换个强智学校大概率原样复现）：

1. **分页参数是 `pageNum`/`pageSize`**（强智 `window.initQzTable` 自定义），
   用 layui 默认的 `page`/`limit` 拿不到数据；超出单页按 `count` 翻页。
2. **接口地址不带 `.do`**。带 `.do` 的同名地址返回「系统功能暂未开放」页面——
   那是校方的功能开关，必须与「接口正常但 `count=0`」区分开，报错文案不能混。
3. 考试时间 `kssj` 是单字符串 `"2026-05-18 08:30~09:55"`，拆成 date/startTime/endTime 再用；
   成绩是双字段：`zcj`（数值）+ `zcjstr`（字符串，等级制「优」时数值为空），
   **展示一律以 `zcjstr` 为口径**；`kz=1` 表示「请评教」，成绩被锁定不显示分数。

关键字段对照（强智原名 → 语义）见 `data/jw/ExamScheduleParser` 的 KDoc（考试导入尚未迁到正方）。

---

## 5. 链路 B：App 端 WebView 导入（生产路径）

实现集中在 `ui/jwvw/JwImportScreen.kt`（约 1200 行，导入全流程）+
`data/jw/` 各解析器。流程：**用户登录 → 判页 → 注入抽取 → Kotlin 解析 → 确认 → 入库**。

### 5.1 入口与会话

- 导入窗口是**独立 Activity**（`JwImportActivity`），不用 NavHost 子页：
  导入时主界面布局完全不动，写库走 `Graph` 单例 + Flow，返回后课表自动刷新。
- 首次加载从 **预热入口**（`JwUrls.SSO_WARMUP`，即教务域 `sso.jsp`）进而不是 CAS 直链：
  WebView 从未访问过教务域时，CAS 回跳的第一次 `sso.jsp?ticket=` 会 500（缺 `bzb_njw`
  cookie），而那个 500 响应会顺手写下 cookie——这就是「第一次必挂、刷新又好」的全部原因。
  先落教务域再跳认证，把这个竞态消掉。
- WebView 配置要点（`configureForJw`）：
  `useWideViewPort + loadWithOverviewMode`（教务页没有 viewport meta，是固定宽桌面布局）；
  `setLayerType(LAYER_TYPE_SOFTWARE)`（MIUI 上硬件合成层会丢，表现为「功能正常但整面白屏」）；
  CookieManager 接受第三方 cookie（CAS 与教务是两个域）。
- **会话探测**：教务把「未登录」就地渲染成 HTTP 200 的登录页（URL 不变），
  所以每次 `onPageFinished` 先注入探针检查 `#loginDiv`/`input[type=password]`
  （不要按文案找——实测登录页上没有「用户没有登录」五个字），且**只在教务域判定**，
  在 CAS 域判定会把用户正在登录误报成会话失效。

### 5.2 DOM 抽取：`evaluateJavascript` 注入

点击导入时，按当前 URL 判定页面类型（`JwUrls.schedulePageKind`）选择注入脚本：

```kotlin
val extractJs = when (pageKind) {
    JwSchedulePage.Theory -> QiangzhiScheduleParser.EXTRACT_JS
    JwSchedulePage.Lab    -> SyjxScheduleParser.EXTRACT_JS
    ...
}
webView.evaluateJavascript(extractJs) { raw -> ... }   // raw 是包了一层引号的 JSON 字符串
```

注入脚本与 §4 的 Python 解析是同一套算法（列序 + colspan/rowspan carry），
返回 `JSON.stringify({ ok, items, term, url })`；Kotlin 侧用
`kotlinx.serialization` 解析（`unwrapJsString` 先剥掉外层引号与转义）。
每个解析器还提供一份正则版 `parseFromHtml`，供 JVM 单测跑 HTML fixture、不依赖真机。

经验：**两张课表的 URL 都含 `xskb`**（理论 `xskb_list`、实验 `toXskb`），
不能用子串判型，必须按页面特征分别匹配。

### 5.3 同源 fetch JSON（考试/成绩）

考试与成绩不解析 DOM，直接复用用户的登录态发同源请求。难点：
`evaluateJavascript` **不会 await Promise**，异步结果收不回来。方案是
「注入 fire-and-forget 的 fetch 脚本 + Kotlin 轮询窗口变量」：

```js
// FETCH_JS：结果写进 window.__qzJson（"ERR:..." 表示失败）
window.__qzJson = null;
fetch('/jsxsd/xsks/xsksap_list?xnxqid=...&pageNum=1&pageSize=200',
      { credentials: 'same-origin' })
  .then(function(r){ return r.text(); })
  .then(function(t){ window.__qzJson = t; })
  .catch(function(e){ window.__qzJson = 'ERR:' + String(e); });
```

```kotlin
// Kotlin 侧：每 300ms 读一次，最多 60 轮（≈18s 超时）；页面不跳转，窗口变量不会丢
suspend fun fetchJsonInWebView(wv, fetchJs, readJs): String? { ... }
```

安全细节：学期号先从壳页下拉读出，再用**白名单正则** `\d{4}-\d{4}-\d` 校验，
才允许拼进注入 JS 的字符串——既防脏数据落库，也从根上杜绝引号注入。

### 5.4 确认与入库

解析结果不直接写库，先过确认弹窗（`ImportTargetDialogHost`）：

- 用户**强制选择**目标课表（可新建）与合并/覆盖方式——多课表之后
  「导到当前课表」不再是唯一合理解释，静默覆盖正在用的数据不可接受；
- 弹窗展示解析出的条数与页面学期（`term`）；
- 考试导入在确认时用**目标课表**的开学日重新映射周次（预览口径 ≠ 落库口径）；
- 入库走 `ScheduleRepository.importParsedCourses`：合并按 mergeKey 去重，
  覆盖整体替换；整批颜色按课程名排序名次分配（§6.4）。

### 5.5 失败呈现与自愈

导入链路大部分故障来自 WebView，处理原则：

- **HTTP 4xx/5xx 只有 `onReceivedHttpError` 能看到**——Chromium 把 5xx 响应体当普通
  页面渲染，`onPageFinished` 照常回调，`onReceivedError` 只管传输层错误；
- 失败页的 `onPageFinished` 仍会到达并把错误态「洗成正常」，需要按
  失败 URL 标记 + 相等匹配一次性消费（实测回调顺序是
  `onReceivedHttpError → onPageStarted → onPageFinished`，错误先到）；
- CAS ticket 是一次性票据：错误浮层的「重试」区分「reload 失败页」与
  「回认证入口重新走链路」，重放已消费的 ticket 只会再挂一次；
- 认证链上的 500 是**自愈型**（响应同时写下缺失的 cookie），自动重试一次，
  真故障时不无限重试打服务端；
- 诊断文案集中在 `data/jw/JwImportDiagnosis.kt`，含 VPN/代理场景识别（`JwVpnDetector`）。

---

## 6. 核心算法与口径（改代码前必读）

这些是全 App 共享的**唯一口径**，任何新功能都应复用而不是重写：

1. **课程时间只有一条口径**：`ScheduleCalculator.courseStartMinutes / courseEndMinutes`
   （自定义时间课以 `customStart/EndTime` 为准，否则查作息表）。
   排序、行内时刻、倒计时、进度条、下一节判定全部走它。
   自己再写一个 `isCustomTime` 分支，自定义时间课就会排错序。
2. **周次计算**：`weekNumberOf` = `startOfWeek(开学日)` 到 `startOfWeek(目标日)` 的周数 + 1，
   两侧都归一化到所在周的周一（`startOfWeek`），开学日填一周内的哪天都不影响周次。
3. **网格列位置**：可见星期序列以 `ScheduleCalculator.visibleDays` 为唯一来源、
   `columnOf` 取列下标。允许单独隐藏周六/周日之后，`day - 1` 不再等于列号
   （隐藏周六但显示周日时整体错位）。
4. **配色必须稳定可复现**：不能用 `name.hashCode()`（JVM 字符串哈希带随机盐，
   两次运行结果不同；且中文课名 12 桶内必撞色）。批量导入用
   `colorIndexesBySortedName`（按课程名排序取名次，同一份课表每次导入颜色一致），
   单条新增用 `nextColorIndex`（不占用已用色），渲染期撞色兜底用
   `weekColorOverrides`（只影响显示、不改库）。
5. **作息表结构版本**存 DataStore（`DisplayPrefsStore.slotSchemaVersion`）：
   改默认作息时要同时升版本号并写一次性迁移，老安装才能拿到新表；
   用户自定义过（`slotsCustomized`）则永不覆盖。

---

## 7. 考试安排映射

考试没有独立实体，`domain/ExamMapper.kt` 把接口行映射成 `Course(kind = Exam)`：

| 接口字段 | 落点 |
|----------|------|
| 考试日期 | 按学期配置反推 → `weeks`（单元素）+ `day` |
| `kssj` 起止时刻 | `customStartTime/EndTime`（`isCustomTime = true`），今日页时刻/倒计时口径自动生效 |
| 起止时刻 | 按作息表映射到**相交小节**（半开区间判定，如 08:30~09:55 → 1-2 节）；落在空档时取下一节兜底 |
| 考场 `js_mc` | `position` |

日期超出学期范围（或开学日未配置）时返回 null：导入层跳过并计数提示，
不静默丢弃也不落错周。历史/未来学期没有开学日配置时，按学期号
（如 `2026-2027-1`）**估算开学日**兜底，并在确认弹窗明示「可能与实际相差一两周」。

---

## 8. 换一所学校：适配指南

把本仓库改造成你自己学校的课表，工作量集中在**数据获取层**；
UI、存储、小组件等全部可以原样复用。建议顺序：

### 第 1 步：认清你的教务系统

浏览器登录教务，看两样东西：

- **URL 形态**：`/jsxsd/...`、`.do` 结尾 → 强智（本项目）；`xskbcx.aspx` → 正方；
  其他常见厂商还有青果、金智、URP 等，各自有一套页面族。
- **页面渲染方式**：服务端直出 HTML 表格（照 §4 逆向 DOM），或前端调 JSON 接口
  （照 §4.4 抓包逆向接口）。很多系统两者混用——像本校：课表是 DOM，考试/成绩是 JSON。

### 第 2 步：存快照、跑通 Python 链路

1. 登录后把目标页面「另存为」完整 HTML，放在本机任意临时目录（勿提交含个人信息的快照）；
2. 仿照 `jw_session.py` 写你学校的登录链路（CAS 通常大同小异，注意 `service` 参数）；
3. 仿照 `fetch_courses.py` 写解析：先跑通「能拿到课程名列表」，再补星期/周次/节次。
   排错时对照快照与 `*_raw.json` 中间产物，而不是凭想象猜 DOM。

### 第 3 步：App 端替换常量与解析器

| 要改的位置 | 内容 |
|------------|------|
| `data/jw/CourseImporter.kt` 的 `JwUrls` | CAS/SSO/课表/考试/成绩全部 URL 与 `schedulePageKind` 的判型规则 |
| `QiangzhiScheduleParser.EXTRACT_JS` 等注入脚本 | 换成你学校页面的 DOM 选择器与字段抽取 |
| `ExamScheduleParser` / `ScoreParser` | JSON 接口地址、参数名、字段名 |
| `data/DefaultData.kt` | 作息表（从教务页行标签逐节核对，不要拍脑袋）与默认开学日 |

解析器契约保持不变：注入 JS 返回 `{ok, items, term}`，Kotlin 侧把每条数据转成
`Course`。下游（确认弹窗、入库、网格渲染）完全不用动。

### 第 4 步：换品牌信息与包名

- `applicationId`、应用显示名（`app/src/main/res/values/strings.xml`，debug 变体在
  `app/src/debug/res/values/strings.xml`）；AGENTS.md 记录了 debug 后缀共存的机制；
- 免责声明改挂你自己的学校；**不要**保留「江西水利电力大学」字样或本项目的
  教务地址（否则你的用户会连到我们的教务）。

### 第 5 步：用单测锁住解析器

把第 2 步的 HTML 快照裁剪后放进 `app/src/test/` 当 fixture
（先抹掉学号姓名等个人信息），仿照 `QiangzhiScheduleParserTest` 写断言。
教务改版时：重抓快照 → 跑测试看哪些断言挂了 → 改解析器。**对着过期结构改代码
是教务解析的头号事故来源。**

---

## 9. 测试

```powershell
.\gradlew.bat :app:testDebugUnitTest          # 纯 JVM，秒级
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline
```

覆盖面（约 30 个测试类，`app/src/test/`）：

- **解析器**：`ZhengfangScheduleParserTest`（正方课表 DOM）、`ZhengfangScoreParserTest`（正方成绩）、
  `ZfJwSessionTest`（无界面登录）、`ExamScheduleParserTest`（考试 JSON，强智口径待迁）、
  `ImportJsonShapeTest`（互通 JSON 形状）、`TermFormatTest`（学期口径）；
- **领域算法**：`ScheduleCalculatorTest`（周次/时刻/配色）、
  `TimeSlotRulesTest` / `TimeSlotScheduleTest`（作息不变量）、
  `WeekGridLayoutTest`（网格几何）、`TodayStateTest` / `TodayBoundaryTest`、
  `ExamMapperTest`（考试映射含历史学期估算）、`ScoreCalculatorTest` / `ScoreGroupsTest`；
- **规划与边界**：`CourseTweakTest`（调课规划）、`ShortcutsTest`、`WidgetModelTest`。

结论从 `app/build/test-results/testDebugUnitTest/*.xml` 汇总
（Gradle 成功时不打印用例数；读 XML 记得 `-Encoding UTF8`）。

---

## 10. 构建环境与已知坑

| 项 | 现值 / 坑 |
|----|-----------|
| Gradle / AGP | Wrapper 8.10.2 / AGP 8.7.3 |
| Kotlin | 2.1.21（compose / serialization / KSP 同版本） |
| Room | **2.7.1**——2.6 配 Kotlin 2.1 会 KSP `unexpected jvm signature V` |
| HugeIcons | `com.github.rikkahub:hugeicons-compose:1.4`（JitPack，**必须 `isTransitive = false`**，否则拉 androidx.core 1.17 编不过）；查名用 `.agents/skills/find-hugeicons/SKILL.md` 的本地 JAR 方法 |
| Glance | 1.2.0（传递抬 compose runtime 到 1.7.8，`androidx.core` 保持 1.15.0） |
| debug/release | 两个 applicationId（`.debug` 后缀），可共存；启动 debug 包必须写全限定 Activity 名 |
| WebView | MIUI 白屏 → 软件渲染兜底；教务页无 viewport meta → `useWideViewPort` 方案 |

---

## 11. 安全与合规红线

- **App 端代码里永远不出现学号/密码/token**：登录由用户在 WebView（导入）或设置页（调课检测，
  凭证进 Android Keystore 加密存储）亲手完成；
- 公开仓库的文档、注释、示例里不得出现真实学号、姓名、手机号；任何含真实数据的抓取产物一律不入库；
- 模拟登录/抓取以「正常客户端」为限：不刷积分、不绕过付费、不伪造官方身份、
  不对教务接口做高频请求；
- 对外发布你的改编版时，同样写明非官方声明，使用风险自负。

---

## 参考

- [DESIGN.md](DESIGN.md) —— 产品与界面规格（§3 UI、§4 技术架构逐模块决策记录）
- [AGENTS.md](AGENTS.md) —— 给 AI 结对工具的工程约定（版本实况、口径清单）
- [拾光课程表](https://github.com/XingHeYuZhuan/shiguangschedule) —— JSON 互通格式参照

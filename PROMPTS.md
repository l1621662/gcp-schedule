# JUWP Schedule · AI 编码提示词包

按阶段复制对应块到 Cursor / Claude Code / 本体 MiMo 等工具。

> **阶段状态**：P0–P5b 已完成，P6 打磨进行中（见 `README.md` 里程碑表）。
> 下方各阶段块保留作**历史记录与复用范式**，不要照着重做已完成的阶段。

**阅读纪律（2026-09-18 起）**：不要通读 `DESIGN.md`，按 `AGENTS.md` 的「必读顺序」只读相关章节。
历史实现记录在 `docs/devlog.md`（仅本地）。

通用粘贴头（每次新会话建议带上）：

```text
项目：JUWP Schedule（江西水利电力大学课表 App）
根目录：F:\JUWP-schedule
必读：AGENTS.md（含必读顺序与工程实况）；DESIGN.md 只读与任务相关的章节
技术栈：Kotlin + Jetpack Compose + Material3，单模块 :app
硬约束：
1) 路线 D：全新 Compose，不 fork 拾光仓库
2) 不重造轮子：图标 HugeIcons；能用官方/主流库就不要自研
3) 课表 JSON 字段对齐拾光互通模型（见 DESIGN.md 4.3）
4) 胖乖 API 以本地 F:\light-life-v3.0 为准，禁止实现刷积分
5) 改导航或课表领域模型前，先改 DESIGN.md 对应章节
6) 改完代码要能说明如何在 Android Studio 跑起来
```

---

## P0 · 规划确认（已完成）

产出：`DESIGN.md`、`PROMPTS.md`、`AGENTS.md`、`README.md`  
不需要写 Kotlin。

---

## P1 · 工程脚手架

**提示词：**

```text
请按 DESIGN.md 初始化 Android 工程到 F:\JUWP-schedule：

1. 创建标准 Gradle Kotlin DSL 工程（settings.gradle.kts / build.gradle.kts / gradle.properties / wrapper）
2. 应用模块 :app
   - applicationId edu.gcp.schedule（已确认）
   - minSdk 26，Compose 打开
3. 依赖：Compose BOM、Material3、Navigation、ViewModel、Retrofit、OkHttp、kotlinx-serialization 或 Moshi、DataStore、Room、me.rerere:hugeicons-compose
4. 主题：Material3 明暗主题，应用名「城职课表」
5. 三个空页面 + 底部导航：今日 / 课表 / 我的（路由名见 DESIGN 3.1）
6. 首页占位文案说明后续功能
7. README 写清：如何用 Android Studio 打开、如何安装 JDK/SDK 要求

不要引入业务网络逻辑。完成后给出目录树和首次构建注意事项（若当前环境无法装 Android SDK，仍交付完整可导入工程文件）。
```

**验收：** AS 能 Sync；安装后能看到 3 Tab。

---

## P2 · 课表领域 + 本地库

**提示词：**

```text
在现有工程上实现课表 domain + data.local，严格用 DESIGN.md 4.3 模型：

1. 实体/DAO：Course、TimeSlot、SemesterConfig
2. 默认作息表（可配置）：第1–5大节或 1–12 小节的 startTime/endTime，先给一套合理默认（如 8:00 起，45+10），写在 DefaultData.kt
3. Repository：getAllCourses、upsert、delete、按周过滤本周课程
4. 计算工具：今天第几周、当前节次、本周课程列表（含自定义时间课）
5. 单元测试：周次计算、节次判断、weeks 过滤

只做 domain/data/测试与最少 UI 接线，不做胖乖、不做 WebView。
```

**验收：** 测试通过；可在 Debug 页面塞 3 门假课。

---

## P2b · 今日 / 周课表 UI

**提示词：**

```text
实现 TodayScreen 与 WeekScreen，UI 气质对齐 DESIGN 3.2–3.3（参考拾光，不抄代码）：

1. 今日：时间轴列表、下一节课高亮、空态 CTA「添加课程」
2. 周课表：星期×节次网格；左右滑切换周；点课程进编辑；点 + 快速加课
3. 课程编辑 BottomSheet：名称/教师/地点/星期/起止节/周次多选（支持单双周常用按钮）
4. 课程颜色：按课程名 hash 稳定映射色板
5. 图标一律 HugeIcons：先用 find-hugeicons 的方法查名再写代码
6. 深色模式正确

主题色不要写死刺眼高饱和；格子圆角 12dp。
```

**验收：** 不连网可完成增删改课，周次切换正确。

---

## P3 · JSON 导入导出

**提示词：**

```text
实现课表 JSON 导入导出，字段与 DESIGN 4.3 一致（兼容拾光风格）：

1. 导出：Activity Result / SAF 选择路径写 UTF-8 JSON
2. 导入：选择文件 → 校验 → 预览课程数量 → 确认覆盖或合并（合并=同名同节去重）
3. 解析失败给出可读错误（缺 weeks、day 越界等）
4. 可选：从剪贴板导入 JSON

提供假数据样例 assets/sample_courses.json 供测试。
```

**验收：** 导出再导入数据一致；坏文件有错误提示。

---

## P4 · 胖乖生活模块

**提示词：**

```text
集成胖乖生活，网络层参照本地 F:\light-life-v3.0（ApiConfig/DeviceApi/AppRepository），包路径建议 data.qiekj + ui.water。

必须实现：
1. 短信验证码登录 + Token 粘贴登录；Token 加密存储
2. user/balance 余额展示（积分、可抵扣金额、小票若有）
3. goods/latestUsed 拉历史设备；选中设备一键 goods/water/unlock（含 light-life 中 unlock 前必要的风控/通道调用，以参考代码为准）
4. goods/water/sync 与 order/detail 尽力而为；本地保存订单快照列表
5. 设置项：自动打开 App 时是否静默尝试签到（默认关闭）；不要做刷积分
6. UI：WaterScreen + 我的页入口卡片 + 今日页「一键开水」快捷入口
7. 免责声明字符串资源

若参考代码与 API 冲突，以 F:\light-life-v3.0 能跑通的调用顺序为准，并在代码注释注明假设。
不要把 token 打进 Logcat。
```

**验收：** 真机/可访问网络环境下完成登录→开水主路径；失败可重试。

---

## P5 · 江西水利电力大学教务导入

**前置（2026-09 已打通，可直接做）：**

- CAS：`https://eapp2.juwp.edu.cn:9443/cas/login?service=...`
- 教务：强智 `https://jiaowu.juwp.edu.cn:81/` → SSO 后 `http://jiaowu.juwp.edu.cn:8080/jsxsd/`
- 课表：`/jsxsd/xskb/xskb_list.do?viweType=0`
- 参考实现：`scripts/fetch_courses.py`
- **不要**把学号密码写进 App；WebView 由用户登录

**提示词：**

```text
实现教务导入模块 ui.jwvw + data.jw：

1. 设置中配置 importUrl：https://jiaowu.juwp.edu.cn:81/ （或门户→教务）
2. 全屏 WebView 加载；登录走统一身份认证（CAS）
3. 工具栏：刷新、后退、导入、关闭
4. 导入：解析 /jsxsd/xskb/xskb_list.do?viweType=0 页面（强智 qz-hasCourse 结构）
   或监听登录后的 xsd 会话再抓该 URL；产出 List<Course>
5. 解析结果预览列表 → 确认写入 Room
6. 失败时 WebView 保持可手动看页面，并提示「可截图/复制课程表后走手动导入」
7. 与拾光互通：导入前后可导出 JSON

WebView 设置：DOM storage 开启；不要关闭 HTTPS 校验。
本地已有样例：scripts/out/courses.json 与 scripts/out/xskb_vt0.html
```

**验收：** 在校园网/教务可达网络下走通登录导入；不可达时错误态友好。

---

## P5b · 实验课表导入

**前置：** 先读 `DESIGN.md` §4.8。若 `scripts/out/syxkb.html` 不存在，**先只做 Step 0 快照采集**，不要凭猜测写解析器。

**提示词：**

```text
按 DESIGN.md 4.8 实现实验课表导入。分两步，先做 Step 0：

Step 0（若缺快照）：
在 ui/jwvw 的 JwImportScreen 加一个仅 debug 生效的入口（长按「导入本页」）：
把 document.documentElement.outerHTML 写入 filesDir/snapshots/snap_<epoch>.html，
Toast 提示路径。完成后我用 adb pull 取回，放进 scripts/out/syxkb.html。
不要在这一步写任何解析逻辑。

Step 1（拿到快照后）：
1. domain：新增 enum CourseKind { Theory, Lab }，Course 增 kind 字段（默认 Theory）
2. data/local：CourseEntity 增 kind:String 默认 "theory"；JuwDatabase version 1→2，
   写 Migration 用 ALTER TABLE courses ADD COLUMN kind TEXT NOT NULL DEFAULT 'theory'，
   禁止 destructive migration
3. mergeKey() 追加 kind
4. data/repo：CourseJson 增 kind（带默认值，保证旧 JSON 可读），导出时写出
5. data/jw/SyjxScheduleParser.kt：注入 JS 抽 {name, detail, day, sections, weekGroup}，
   按 (name, day, startSection, endSection, teacher, position) 聚合、weeks 取并集；
   weekGroup 取不到时用 parseWeeks(detail) 兜底。不改动 QiangzhiScheduleParser
6. data/jw/JwUrls：加 LAB_SCHEDULE 与 isLabScheduleUrl()
7. JwImportScreen：底部改 [理论课表] [实验课表] + [导入本页]，按 URL 自动选解析器，
   不匹配时提示语明确；确认弹窗显示「共 N 条，其中实验课 M 条」
8. ui/common/CourseBlock：kind=Lab 且跨度≥2 节时右下角显示「实验」角标（白 70%）
9. ui/week：顶部加「全部/理论/实验」筛选，默认全部
10. 修同格重叠：重叠课程均分列宽并排，不再互相覆盖

单测用 scripts/out/syxkb.html 作 fixture（参照 QiangzhiScheduleParserTest 的写法）。
输出：改动文件清单、如何构建、如何验证、已知风险。
```

**验收：** 真机 WebView 登录 → 实验课表 → 导入 → 周课表出现实验课且周次正确；单测通过。

---

## P6 · 打磨

```text
按 DESIGN 做发布前打磨：
1. 明暗主题与动态色
2. 空态/加载/错误三态统一组件
3. 课表网格无障碍 contentDescription
4. ProGuard：Retrofit/OkHttp/Moshi 或 kotlinx-serialization keep
5. 版本号、关于页、免责声明
6. 列出建议真机测试清单（小米/华为/OPPO 各一）
```

---

## 专题提示词：只写教务 JS 适配（可选支线）

若你其实更想走「官方拾光 + 私有适配」而不是路线 D，可用：

```text
按拾光 wiki「如何适配教务v2」，为江西水利电力大学写 cust.js：
- 使用 window.shiguangBridgePromise 完成弹窗、saveImportedCourses、saveCourseConfig
- 兼容 CourseJsonModel / TimeSlotJsonModel
- 参考 shiguang_warehouse 结构准备 adapters.yaml 字段
- 先在 shiguang_Tester 浏览器环境可测
```

---

## 提示词使用纪律

1. 一次只执行一个阶段，做完再进下一阶段。  
2. 改架构前先改 `DESIGN.md`，禁止只在对话里改方案。  
3. 出现“为了跑通”而注释掉报错、绕过校验、写死 token 的代码：直接打回。  
4. 新依赖必须说明为什么现有依赖不够。  
5. 大 diff 前先列文件清单和风险点。

---

## 常用话术（你自己可改）

- 「先读 DESIGN.md 再动手，只做 P2，不要提前写胖乖。」  
- 「图标用 hugeicons，先查名。」  
- 「这个接口调用顺序以 F:\light-life-v3.0 的 DeviceApi + AppRepository 为准。」  
- 「教务 URL 我还没给你，只做 ManualImporter。」  
- 「输出：改动文件列表、如何构建、如何验证、已知风险。」

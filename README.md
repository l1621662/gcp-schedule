# JUWP Schedule（水贝贝）

江西水利电力大学的课表 Android App —— 水专也有自己的 App 了！

<img src="screenshots/week.png" width="49%" alt="课表页"> <img src="screenshots/today.jpg" width="49%" alt="今日页">

> **非学校官方应用**，学生自用学习项目，详见文末[免责声明](#免责声明)。

## 功能

**课表**

- 一键从教务系统导入学期课表，自动定位并解析课表页，无需手动寻找课表位置
- 支持一键导入理论课表、**实验课表**与**考试安排**，并合并进同一张课表
- 上课提醒、系统日历同步、调课规划

**成绩**

- 一键导入全部学期成绩，按学期 / 学年分组，自动计算加权平均分与平均绩点

**扩展功能**

- 胖乖生活：一键开水、余额查询、订单记录
- 快趣出行码：输入车号生成骑行二维码，一键拉起微信扫一扫，扫完返回后自动删除相册中的二维码图片
- 校园卡付款码：一键出示，喝水吃饭、寝室门禁不再需要水宝宝！支持查看一卡通余额与历史账单，凭证加密存本机，可随时关闭
- 取快递快捷方式：一键直达拼多多取件码、淘宝身份码与菜鸟（无广告启动），排队取快递不再匆忙

**导入与备份**

- 课表、成绩等数据支持本地备份，可随时导出与导入

## 使用

课表默认为空、不预置样例：

- 课表：**我的 → 教务导入**，登录教务导入学期课表
- 成绩：**我的 → 成绩查询**页内导入
- 考试：在教务导入 WebView 内打开考试安排页，一键导入

---

# 开发

整体架构、教务爬虫解析、WebView 导入实现与换校适配步骤，见开发者文档 **[DEVELOPER.md](DEVELOPER.md)**。

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

## 参考

- 拾光课程表：https://github.com/XingHeYuZhuan/shiguangschedule
- 胖乖参考实现：https://github.com/Inonvation/light-life
- HugeIcons Compose：`com.github.rikkahub:hugeicons-compose`（JitPack）

## 免责声明

本项目为学生自用学习项目，**非学校官方应用**，与江西水利电力大学无任何隶属或合作关系。

- 登录教务、调用胖乖生活与校园卡（一卡通）接口等行为均模拟正常客户端操作，存在账号与接口变更风险，使用后果自负。
- 应用不上传任何数据到第三方服务器；教务与校园卡凭证仅保存在本机（Android Keystore 加密存储，已排除云备份），凭证不写入代码仓库。
- 校园卡付款码等同现金：仅限本人使用，请勿截图或分享给他人，泄露可能被盗刷；若因启用相关功能造成损失，开发者本人概不负责。
- 严禁将本项目用于刷积分、绕过付费或任何伪造官方身份的用途。
- 禁止在学校（含水专及其他任何学校）内售卖该软件及其脚本，一经发现将永久删除此仓库，由此造成的损失本人概不负责。
- 严禁将本项目分享的脚本用于攻击学校网站、爬取售卖本校敏感数据、逆向学校官网或教务系统等违规违法行为。
- 项目仅供学习交流使用，请于下载软件后 24 小时内删除；如因不正当使用造成任何损失，本人概不负责。如有侵权，请联系删除。

## License

[MIT](LICENSE)

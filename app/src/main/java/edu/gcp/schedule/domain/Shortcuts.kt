package edu.gcp.schedule.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 一条快捷方式。目标三种填法（互不排斥，按 [resolveLaunchPlan] 的优先级折算）：
 * 只填包名 = 打开 App；包名 + Activity = 直达页面（菜鸟跳开屏广告就靠这个）；
 * 填链接 = 打开链接（https 或私有 scheme，可再配包名限定宿主）。
 *
 * `presetIndex >= 0` 表示预设槽（0=拼多多 1=淘宝 2=菜鸟）：
 * 图标与「重置为默认」都按它从 [Shortcuts.PRESET_SHORTCUTS] 派生，自定义条目恒为 -1。
 *
 * [icon] 只对自定义条目生效：存的是 UI 图标注册表的 key（ui/common/ShortcutUi），
 * 空 = 用默认链接图标。带默认值，加字段前的旧 JSON 照常解码。
 */
@Serializable
data class ShortcutItem(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val pkg: String = "",
    val activity: String = "",
    val presetIndex: Int = -1,
    val icon: String = "",
)

/** 开关 + 条目列表，今日页与设置页共用的一个快照口径。 */
data class ShortcutSettings(
    val enabled: Boolean = true,
    val items: List<ShortcutItem> = Shortcuts.PRESET_SHORTCUTS,
)

/**
 * 拉起口径。domain 层只决定「怎么拉」，Intent 构造与异常翻译在 ui/common/ShortcutLauncher，
 * 分开的原因：Intent/Uri 是 android.framework 类，进了 domain 整个文件就没法 JVM 单测。
 */
sealed interface ShortcutLaunchPlan {

    /** 显式组件直达：`setClassName(pkg, activity)`。无任何兜底——Activity 不存在就是不存在。 */
    data class ExplicitComponent(val pkg: String, val activity: String) : ShortcutLaunchPlan

    /** 打开链接，[pkg] 非空时限定宿主；拉不起时宿主可去（执行层负责去包名重试）。 */
    data class UriView(val uri: String, val pkg: String?) : ShortcutLaunchPlan

    /** 只填包名：拉起目标 App 的启动页。 */
    data class LaunchPackage(val pkg: String) : ShortcutLaunchPlan
}

object Shortcuts {

    /** 条目总数上限（含预设）：今日页是一行横滑 chips，再多就得翻页了。 */
    const val MAX_COUNT = 8

    /**
     * 内置预设。拼多多/淘宝两条目标取自 light-life 实测可用的值；
     * 菜鸟走显式 Activity 直达首页（跳过开屏广告），Activity 名变更时用户可在编辑表单里自己改。
     */
    val PRESET_SHORTCUTS = listOf(
        ShortcutItem(
            id = "preset_pdd",
            name = "拼多多取件码",
            uri = "pinduoduo://com.xunmeng.pinduoduo/mdkd/package",
            pkg = "com.xunmeng.pinduoduo",
            presetIndex = 0,
        ),
        ShortcutItem(
            id = "preset_taobao",
            name = "淘宝身份码",
            uri = "https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/identity-code",
            pkg = "com.taobao.taobao",
            presetIndex = 1,
        ),
        ShortcutItem(
            id = "preset_cainiao",
            name = "菜鸟（无广启动）",
            pkg = "com.cainiao.wireless",
            activity = "com.cainiao.wireless.homepage.view.activity.HomePageActivity",
            presetIndex = 2,
        ),
    )

    private val format = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 预设表的历史版本（最新改动在前）。
     * **维护契约**：改 [PRESET_SHORTCUTS] 里任何预设的目标或名称前，先把改动前的整张表
     * 追加到这里，否则老用户的「未编辑预设槽」无法自动升级到新目标。
     */
    val PRESET_HISTORY: List<List<ShortcutItem>> = listOf(
        // v1 → v2（2026-09-20）：菜鸟改名「菜鸟（无广启动）」，目标是让它自述「跳开屏广告」
        // 这一差异（目标字段未变，但整表入史才能让未编辑槽自动换名）。
        listOf(
            ShortcutItem(
                id = "preset_pdd",
                name = "拼多多取件码",
                uri = "pinduoduo://com.xunmeng.pinduoduo/mdkd/package",
                pkg = "com.xunmeng.pinduoduo",
                presetIndex = 0,
            ),
            ShortcutItem(
                id = "preset_taobao",
                name = "淘宝身份码",
                uri = "https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/identity-code",
                pkg = "com.taobao.taobao",
                presetIndex = 1,
            ),
            ShortcutItem(
                id = "preset_cainiao",
                name = "菜鸟",
                pkg = "com.cainiao.wireless",
                activity = "com.cainiao.wireless.homepage.view.activity.HomePageActivity",
                presetIndex = 2,
            ),
        ),
    )

    /**
     * 预设目标自动迁移：存储里的预设槽如果与某个历史版本的预设**目标字段完全一致**，
     * 说明用户没动过目标——整条升级到当前预设（目标与名称都换新）；
     * 只改过名的保留用户名、目标照样升级；改过目标的槽位不动。
     *
     * 不在存储里记版本号：与所有历史值都不匹配就视为自定义，天然幂等，
     * 且跨多个版本直接跳迁也成立（每个历史表只用来识别「从那时起没动过」）。
     */
    fun migratePresets(
        items: List<ShortcutItem>,
        history: List<List<ShortcutItem>> = PRESET_HISTORY,
    ): List<ShortcutItem> {
        if (history.isEmpty()) return items
        return items.map { item ->
            val index = item.presetIndex
            if (index < 0) return@map item
            val current = PRESET_SHORTCUTS.firstOrNull { it.presetIndex == index }
                ?: return@map item
            val matched = history.asSequence()
                .mapNotNull { table -> table.firstOrNull { it.presetIndex == index } }
                .firstOrNull { old ->
                    item.uri == old.uri && item.pkg == old.pkg && item.activity == old.activity
                }
                ?: return@map item
            if (item == matched) current else current.copy(name = item.name)
        }
    }

    /**
     * 解码失败一律回退预设：shortcuts_json 是自己写自己的数据，真坏了也不该让读路径
     * 抛异常崩掉今日页（口径同 TimetablePrefs.decode）。
     */
    fun decode(text: String): List<ShortcutItem> = try {
        format.decodeFromString(ListSerializer(ShortcutItem.serializer()), text)
    } catch (_: Exception) {
        PRESET_SHORTCUTS
    }

    fun encode(items: List<ShortcutItem>): String =
        format.encodeToString(ListSerializer(ShortcutItem.serializer()), items)

    /** 校验不通过返回用户可读的错误文案；null = 可保存/可执行。 */
    fun validate(item: ShortcutItem): String? {
        if (item.name.isBlank()) return "请填写名称"
        // Activity 是最明确的意图，优先按它判；配套的包名/类名格式在这里挡一道，
        // 别等到 startActivity 抛出看不懂的系统异常
        if (item.activity.isNotBlank()) {
            if (item.pkg.isBlank()) return "填了 Activity 必须同时填包名"
            if (!item.activity.contains('.')) return "Activity 需要完整类名（如 com.example.app.MainActivity）"
            if (!item.pkg.contains('.')) return "包名格式不对（如 com.cainiao.wireless）"
            return null
        }
        if (item.uri.isNotBlank()) {
            if (!item.uri.contains("://")) return "链接缺少协议头（如 https:// 或 pinduoduo://）"
            if (item.pkg.isNotBlank() && !item.pkg.contains('.')) return "包名格式不对（如 com.cainiao.wireless）"
            return null
        }
        if (item.pkg.isNotBlank()) {
            if (!item.pkg.contains('.')) return "包名格式不对（如 com.cainiao.wireless）"
            return null
        }
        return "请填写链接、包名或 Activity 至少一项"
    }

    /** 折算拉起口径；返回 null = 配置不完整（理论上被 [validate] 挡住，执行层仍要防）。 */
    fun resolveLaunchPlan(item: ShortcutItem): ShortcutLaunchPlan? = when {
        item.activity.isNotBlank() && item.pkg.isNotBlank() ->
            ShortcutLaunchPlan.ExplicitComponent(item.pkg, item.activity)
        item.uri.isNotBlank() ->
            ShortcutLaunchPlan.UriView(item.uri, item.pkg.takeIf { it.isNotBlank() })
        item.pkg.isNotBlank() -> ShortcutLaunchPlan.LaunchPackage(item.pkg)
        else -> null
    }

    /** 设置页行尾的目标摘要，单行展示（UI 负责截断）。 */
    fun targetSummary(item: ShortcutItem): String = when {
        item.activity.isNotBlank() -> "${item.pkg}/${item.activity}"
        item.uri.isNotBlank() -> item.uri
        else -> item.pkg
    }
}

/** 列表的纯函数操作：存储层与 ViewModel 都只是搬运，判定逻辑集中在这里（可 JVM 测）。 */
object ShortcutOps {

    fun newCustomId(): String = "custom-${UUID.randomUUID()}"

    /** 追加一条自定义条目；是否超 [Shortcuts.MAX_COUNT] 由调用方先判（UI 要提前禁用按钮）。 */
    fun add(items: List<ShortcutItem>, item: ShortcutItem): List<ShortcutItem> = items + item

    /** 上移/下移（delta = -1/+1）；越界视为无操作，调用方无需先判边界。 */
    fun move(items: List<ShortcutItem>, id: String, delta: Int): List<ShortcutItem> {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return items
        val target = index + delta
        if (target !in items.indices) return items
        val copy = items.toMutableList()
        val moved = copy[index]
        copy[index] = copy[target]
        copy[target] = moved
        return copy
    }

    /** 重置单个预设槽：整条替换回内置值（含 id，与初始一致）。未知 presetIndex 不动。 */
    fun resetPreset(items: List<ShortcutItem>, presetIndex: Int): List<ShortcutItem> {
        val preset = Shortcuts.PRESET_SHORTCUTS.firstOrNull { it.presetIndex == presetIndex }
            ?: return items
        return items.map { if (it.presetIndex == presetIndex) preset else it }
    }

    /** 恢复全部默认：3 条预设 + 调用方自行把开关还原为开。 */
    fun resetAll(): List<ShortcutItem> = Shortcuts.PRESET_SHORTCUTS
}

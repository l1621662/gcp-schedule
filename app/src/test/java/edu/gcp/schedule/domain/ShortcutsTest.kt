package edu.gcp.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快捷方式的纯逻辑口径（DESIGN §4.16）：拉起口径折算、表单校验、预设表、
 * JSON 编解码兜底、列表操作。Intent 构造在 ui/common/ShortcutLauncher（android 类，JVM 测不了）。
 */
class ShortcutsTest {

    // ---- resolveLaunchPlan：三种口径的优先级 = Activity > 链接 > 只填包名 ----

    @Test
    fun plan_activityWins() {
        val plan = Shortcuts.resolveLaunchPlan(
            ShortcutItem(id = "x", pkg = "com.a.b", activity = "com.a.b.Main", uri = "https://a.b"),
        )
        assertEquals(ShortcutLaunchPlan.ExplicitComponent("com.a.b", "com.a.b.Main"), plan)
    }

    @Test
    fun plan_uriCarriesPkgOnlyIfPresent() {
        assertEquals(
            ShortcutLaunchPlan.UriView("https://a.b", "com.a.b"),
            Shortcuts.resolveLaunchPlan(ShortcutItem(id = "x", uri = "https://a.b", pkg = "com.a.b")),
        )
        assertEquals(
            ShortcutLaunchPlan.UriView("pinduoduo://x/y", null),
            Shortcuts.resolveLaunchPlan(ShortcutItem(id = "x", uri = "pinduoduo://x/y")),
        )
    }

    @Test
    fun plan_pkgOnlyLaunchesPackage() {
        assertEquals(
            ShortcutLaunchPlan.LaunchPackage("com.cainiao.wireless"),
            Shortcuts.resolveLaunchPlan(ShortcutItem(id = "x", pkg = "com.cainiao.wireless")),
        )
    }

    @Test
    fun plan_noTargetIsNull() {
        assertNull(Shortcuts.resolveLaunchPlan(ShortcutItem(id = "x", name = "空")))
    }

    // ---- validate：挡在 startActivity 之前的可读错误 ----

    @Test
    fun validate_blankName() {
        assertTrue(
            Shortcuts.validate(ShortcutItem(id = "x", pkg = "com.a.b"))!!.contains("名称"),
        )
    }

    @Test
    fun validate_noTarget() {
        assertTrue(
            Shortcuts.validate(ShortcutItem(id = "x", name = "空"))!!.contains("至少"),
        )
    }

    @Test
    fun validate_activityNeedsPkg() {
        assertTrue(
            Shortcuts.validate(ShortcutItem(id = "x", name = "a", activity = "com.a.b.Main"))!!
                .contains("包名"),
        )
    }

    @Test
    fun validate_activityNeedsFullClassName() {
        assertNotNull(
            Shortcuts.validate(ShortcutItem(id = "x", name = "a", pkg = "com.a.b", activity = "Main")),
        )
    }

    @Test
    fun validate_pkgMustLookLikePackage() {
        assertNotNull(
            Shortcuts.validate(ShortcutItem(id = "x", name = "a", pkg = "cainiao")),
        )
    }

    @Test
    fun validate_uriNeedsScheme() {
        assertTrue(
            Shortcuts.validate(ShortcutItem(id = "x", name = "a", uri = "pages-fast.m.taobao.com/x"))!!
                .contains("协议"),
        )
    }

    @Test
    fun validate_validForms() {
        assertNull(Shortcuts.validate(ShortcutItem(id = "x", name = "a", pkg = "com.a.b", activity = "com.a.b.Main")))
        assertNull(Shortcuts.validate(ShortcutItem(id = "x", name = "a", uri = "https://a.b", pkg = "com.a.b")))
        assertNull(Shortcuts.validate(ShortcutItem(id = "x", name = "a", pkg = "com.a.b")))
    }

    // ---- 预设表：三条、口径与实测一致 ----

    @Test
    fun presets_shape() {
        assertEquals(3, Shortcuts.PRESET_SHORTCUTS.size)
        Shortcuts.PRESET_SHORTCUTS.forEachIndexed { index, item ->
            assertEquals("预设下标应连续 0..2", index, item.presetIndex)
            assertTrue("预设 id 应以 preset_ 开头", item.id.startsWith("preset_"))
            assertNull("预设必须自带合法目标", Shortcuts.validate(item))
        }
        val pdd = Shortcuts.PRESET_SHORTCUTS[0]
        assertEquals("pinduoduo://com.xunmeng.pinduoduo/mdkd/package", pdd.uri)
        assertEquals("com.xunmeng.pinduoduo", pdd.pkg)
        val taobao = Shortcuts.PRESET_SHORTCUTS[1]
        assertTrue(taobao.uri.startsWith("https://pages-fast.m.taobao.com/"))
        assertTrue(taobao.uri.endsWith("identity-code"))
        assertEquals("com.taobao.taobao", taobao.pkg)
        val cainiao = Shortcuts.PRESET_SHORTCUTS[2]
        assertEquals("com.cainiao.wireless", cainiao.pkg)
        assertEquals(
            "com.cainiao.wireless.homepage.view.activity.HomePageActivity",
            cainiao.activity,
        )
        // 菜鸟靠显式 Activity 跳过开屏广告，折算必须是 ExplicitComponent 口径
        assertTrue(Shortcuts.resolveLaunchPlan(cainiao) is ShortcutLaunchPlan.ExplicitComponent)
    }

    // ---- JSON：脏数据回退预设、空列表保持为空、roundtrip ----

    @Test
    fun decode_dirtyJsonFallsBackToPresets() {
        assertEquals(Shortcuts.PRESET_SHORTCUTS, Shortcuts.decode("不是 JSON"))
        assertEquals(Shortcuts.PRESET_SHORTCUTS, Shortcuts.decode("{\"a\":1}"))
    }

    @Test
    fun decode_emptyListStaysEmpty() {
        // 用户删光自定义条目是合法状态，不能被「回退预设」吞掉
        assertEquals(emptyList<ShortcutItem>(), Shortcuts.decode("[]"))
    }

    @Test
    fun decode_encodeRoundtrip_andIgnoreUnknownKeys() {
        val items = listOf(
            Shortcuts.PRESET_SHORTCUTS[0],
            ShortcutItem(id = "custom-1", name = "学校官网", uri = "https://example.edu.cn"),
        )
        assertEquals(items, Shortcuts.decode(Shortcuts.encode(items)))
        // 加字段前导出的旧 JSON（多出未知字段）也要能读
        val future = """[{"id":"c1","name":"n","pkg":"com.a.b","unknownField":1}]"""
        assertEquals(listOf(ShortcutItem(id = "c1", name = "n", pkg = "com.a.b")), Shortcuts.decode(future))
    }

    @Test
    fun decode_iconFieldRoundtrip_andLegacyJsonWithoutIcon() {
        val items = listOf(ShortcutItem(id = "c1", name = "n", pkg = "com.a.b", icon = "rocket"))
        assertEquals(items, Shortcuts.decode(Shortcuts.encode(items)))
        // 加 icon 字段之前的存量 JSON：解码出空 icon（渲染时回退默认链接图标）
        val legacy = Shortcuts.decode("""[{"id":"c1","name":"n","pkg":"com.a.b"}]""")
        assertEquals("", legacy[0].icon)
    }

    // ---- ShortcutOps：增、调序、重置 ----

    @Test
    fun ops_moveSwapsNeighbours_andIgnoresEdges() {
        val list = listOf(
            ShortcutItem(id = "a", name = "1"),
            ShortcutItem(id = "b", name = "2"),
            ShortcutItem(id = "c", name = "3"),
        )
        assertEquals(listOf("b", "a", "c"), ShortcutOps.move(list, "a", 1).map { it.id })
        assertEquals(listOf("a", "b", "c"), ShortcutOps.move(list, "a", -1).map { it.id })
        assertEquals(list, ShortcutOps.move(list, "a", 5))
        assertEquals(list, ShortcutOps.move(list, "missing", -1))
    }

    @Test
    fun ops_resetPresetReplacesInPlace() {
        val edited = listOf(
            Shortcuts.PRESET_SHORTCUTS[0],
            Shortcuts.PRESET_SHORTCUTS[1].copy(name = "被改过的淘宝"),
            Shortcuts.PRESET_SHORTCUTS[2],
        )
        val reset = ShortcutOps.resetPreset(edited, 1)
        assertEquals(3, reset.size)
        assertEquals(Shortcuts.PRESET_SHORTCUTS[1], reset[1])
        assertEquals(edited[0], reset[0])
        assertEquals(edited[2], reset[2])
        // 未知下标不动
        assertEquals(edited, ShortcutOps.resetPreset(edited, 9))
    }

    @Test
    fun ops_resetAllRestoresPresets() {
        val mess = listOf(ShortcutItem(id = "custom-x", name = "x"))
        assertEquals(Shortcuts.PRESET_SHORTCUTS, ShortcutOps.resetAll())
        assertNotEquals(mess, ShortcutOps.resetAll())
    }

    @Test
    fun ops_newCustomIdIsUnique() {
        assertNotEquals(ShortcutOps.newCustomId(), ShortcutOps.newCustomId())
        assertTrue(ShortcutOps.newCustomId().startsWith("custom-"))
    }

    // ---- migratePresets：预设目标自动迁移（历史原值 → 当前值，自定义不动） ----

    /** 构造一个假想的历史版本：拼多多槽位的 uri/name 都与当前不同，其余两条与当前相同。 */
    private val v1Table = listOf(
        Shortcuts.PRESET_SHORTCUTS[0].copy(
            name = "拼多多（旧名）",
            uri = "pinduoduo://old.example/mdkd",
        ),
        Shortcuts.PRESET_SHORTCUTS[1],
        Shortcuts.PRESET_SHORTCUTS[2],
    )

    @Test
    fun migrate_emptyHistoryIsIdentity() {
        // 当前发布态：还没有历史版本，迁移必须是恒等变换
        val items = Shortcuts.PRESET_SHORTCUTS + ShortcutItem(id = "c1", name = "x", pkg = "com.a.b")
        assertEquals(items, Shortcuts.migratePresets(items, history = emptyList()))
    }

    @Test
    fun migrate_untouchedSlotUpgradesWholesale() {
        // 存储里还是历史原值（含旧名）= 用户从未编辑 → 整条升级为当前预设（名与目标都换新）
        val stored = listOf(v1Table[0], Shortcuts.PRESET_SHORTCUTS[1], Shortcuts.PRESET_SHORTCUTS[2])
        val migrated = Shortcuts.migratePresets(stored, history = listOf(v1Table))
        assertEquals(Shortcuts.PRESET_SHORTCUTS[0], migrated[0])
        assertEquals(Shortcuts.PRESET_SHORTCUTS[1], migrated[1])
    }

    @Test
    fun migrate_renamedSlotKeepsNameGetsNewTarget() {
        // 只改过名（目标仍是历史原值）→ 目标升级、用户名保留
        val renamed = v1Table[0].copy(name = "我的取件")
        val migrated = Shortcuts.migratePresets(listOf(renamed), history = listOf(v1Table))
        assertEquals("我的取件", migrated[0].name)
        assertEquals(Shortcuts.PRESET_SHORTCUTS[0].uri, migrated[0].uri)
        assertEquals(Shortcuts.PRESET_SHORTCUTS[0].pkg, migrated[0].pkg)
    }

    @Test
    fun migrate_customizedTargetUntouched() {
        val customized = v1Table[0].copy(uri = "pinduoduo://my-own")
        val migrated = Shortcuts.migratePresets(listOf(customized), history = listOf(v1Table))
        assertEquals(customized, migrated[0])
    }

    @Test
    fun migrate_customItemsAndUnknownPresetIndexUntouched() {
        // 自定义条目（presetIndex=-1）即使字段碰巧等于历史预设也不动；未知下标同样跳过
        val custom = ShortcutItem(
            id = "c1",
            name = v1Table[0].name,
            uri = v1Table[0].uri,
            pkg = v1Table[0].pkg,
        )
        val unknown = v1Table[0].copy(id = "ghost", presetIndex = 9)
        val items = listOf(custom, unknown)
        assertEquals(items, Shortcuts.migratePresets(items, history = listOf(v1Table)))
    }

    @Test
    fun migrate_alreadyCurrentStaysCurrent() {
        // 已是当前值的槽位：迁移后保持不变（幂等）
        val migrated = Shortcuts.migratePresets(Shortcuts.PRESET_SHORTCUTS, history = listOf(v1Table))
        assertEquals(Shortcuts.PRESET_SHORTCUTS, migrated)
    }

    @Test
    fun migrate_publishedHistoryUpgradesUntouchedSlots() {
        // 真实发布态历史（v1 表）：存量用户未编辑的槽位应自动升级到当前预设。
        // 这里同时守住维护契约——v1 表首行必须仍是「拼多多取件码」时代的原值，
        // 若这条断言失败，说明有人改了 v1 表而不是追加新历史（老用户将无法迁移）。
        val v1 = Shortcuts.PRESET_HISTORY.first()
        assertEquals("preset_pdd", v1[0].id)
        assertEquals("pinduoduo://com.xunmeng.pinduoduo/mdkd/package", v1[0].uri)
        assertEquals("preset_cainiao", v1[2].id)
        assertEquals("菜鸟", v1[2].name)
        // 存量未编辑：整表按 v1 存储 → 迁移后应与当前预设完全一致（名与目标都换新）
        val migrated = Shortcuts.migratePresets(v1, history = Shortcuts.PRESET_HISTORY)
        assertEquals(Shortcuts.PRESET_SHORTCUTS, migrated)
    }
}

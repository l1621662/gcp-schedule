package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01

/**
 * 设置行统一网格：开关行 / 跳转行 / 纯展示行**等高**（DESIGN §3.3）。
 *
 * 根因：M3 的 Switch 自带 48dp 最小交互区（minimumInteractiveComponentSize），
 * 开关行的高度 = max(48dp, 文字块) + 上下留白；而跳转 / 展示行的高度只由
 * 「标题 + 说明 + 留白」决定（约 34dp + 留白）。同卡内混排时开关行明显高一截
 * （真机实测：开关行 ≈ 60dp、跳转行 ≈ 54dp），四行就参差成四档。
 * 原先只有 `heightIn(min = 44/52dp)`——**下限低于内容高度等于没写**，压不平两者。
 *
 * 统一口径：所有简单行的内容区下限 = 开关交互区 48dp，行内留白同一份，
 * 于是两者恒等于 max(48dp, 文字块) + 2×4dp，任何字体缩放档位下都相等。
 */
private val SettingsRowContentMinHeight = 48.dp

/** 行内上下留白（上下各一份）；内容在行内垂直居中。 */
private val SettingsRowVerticalPadding = 4.dp

/** 简单行（开关 / 跳转 / 纯展示）的统一高度：48 + 2×4 = 56dp。 */
private val SettingsRowMinHeight = SettingsRowContentMinHeight + SettingsRowVerticalPadding * 2

/**
 * 设置分区卡：标题 + 副标题 + 若干行。
 *
 * 视觉规则（重构后的统一语言）：
 * - 圆角 14dp、无阴影、surfaceVariant 低透明度填充——克制，不做「贴纸感」；
 * - 行首图标统一 34dp 圆角底座，视觉重心一致；
 * - 行与行之间不画分割线，靠 12dp 垂直留白分组（分割线只出现在分区之间）；
 * - 卡内简单行统一 [SettingsRowMinHeight]（见上：开关的 48dp 交互区不再把行撑高）；
 *   带分段控件的 [SettingChoiceRow] 标题块同高、控件条另起一块（三选一塞不进标题行尾部）。
 */
@Composable
fun SettingsSection(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Column(Modifier.padding(top = 4.dp), content = content)
        }
    }
}

/**
 * 单条设置行：标题 + 说明 + 尾部（值文本 / 箭头 / 自定义槽位）。
 * 可点时整行响应并附带触感反馈；不可点（纯展示）时忽略点击。
 */
@Composable
fun SettingItem(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    showArrow: Boolean = onClick != null,
    icon: ImageVector? = null,
    /** 右侧值文本（当前选中值 / 跳转前状态），显示在箭头之前。 */
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** false 时整行置灰且不响应点击（如前置条件未满足 / 动作进行中）。 */
    enabled: Boolean = true,
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SettingsRowMinHeight)
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable {
                        haptics.tap()
                        onClick()
                    }
                } else {
                    Modifier
                }
            )
            .padding(vertical = SettingsRowVerticalPadding)
            .then(if (enabled) Modifier else Modifier.alpha(0.45f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            SettingsIconBadge(icon)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
        }
        when {
            trailing != null -> trailing()
            showArrow -> Icon(
                HugeIcons.ArrowRight01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 行首图标底座：34dp 圆角块，统一所有设置行的视觉起点。
 * 不做彩色 tint 背景（贴纸感），只用主色描一个轮廓图标。
 */
@Composable
fun SettingsIconBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 开关行：标题 + Switch（说明文字可选，显示设置等紧凑面板传 null 收成单行）。
 * 切换时附带触感（toggle 语义）。
 * 行高与 [SettingItem] 同口径（[SettingsRowMinHeight]）：M3 开关自带 48dp 交互区，
 * 这里把它算作「内容下限」而不是额外增量，开关行才不会比跳转行高一截。
 * 供「我的 → 通用」与显示设置共用，保证开关行的交互一致。
 */
@Composable
fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SettingsRowMinHeight)
            .padding(vertical = SettingsRowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            SettingsIconBadge(icon)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                haptics.toggle()
                onCheckedChange(it)
            },
        )
    }
}

/**
 * 选择行：标题（+可选说明）在上，分段按钮整行在下。
 * 分段按钮不塞进标题行尾部——三段选项（如「跟随系统」）在窄屏必然溢出，
 * 整行呈现也统一了「外观主题」「开水点击方式」两处选择交互。
 */
@Composable
fun SettingChoiceRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 标题块与 [SettingSwitchRow] / [SettingItem] 同行高：图标底座与标题的落点
        // 与卡内其余行一致，不会因为下面多一条分段控件而整体上浮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsRowMinHeight)
                .padding(vertical = SettingsRowVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                SettingsIconBadge(icon)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = SettingsRowVerticalPadding),
        ) {
            options.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    // 不显示选中对勾：选中段自带填充色，图标徒增视觉噪音
                    icon = {},
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.height(36.dp),
                )
            }
        }
    }
}

/** 分区之间的细分割线（入口列表里的视觉换气）。 */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

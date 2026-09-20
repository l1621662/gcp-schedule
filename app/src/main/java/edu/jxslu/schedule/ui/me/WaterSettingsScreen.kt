package edu.jxslu.schedule.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.ui.common.SettingChoiceRow
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Droplet
import me.rerere.hugeicons.stroke.GlassWater

/**
 * 开水设置二级页（我的 → 胖乖生活 → 开水设置，DESIGN §3.3）。
 *
 * 2026-09-19 归并：「显示开水卡片」原先放在课表页显示设置弹层（与胖乖无关的
 * 格线/字号开关混在一组），「开水点击方式」直接摊在我的页胖乖卡里；两项都是
 * 胖乖生活的事，收进同一子页。均不依赖登录态——未登录也能关卡片显示
 * （今日页此时展示未登录态卡片）与调整点击方式。
 * 登录 / 退出在「开水」页完成，本页不涉及 token。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaterSettingsScreen(
    onBack: () -> Unit,
    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = rememberAppHaptics()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开水设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(
                title = "今日页开水卡片",
                subtitle = "胖乖生活为第三方服务（非学校官方功能）；卡片未登录时展示未登录态，开关与登录与否无关。",
            ) {
                SettingSwitchRow(
                    title = "显示开水卡片",
                    subtitle = "关闭后今日页底部不再显示开水入口",
                    checked = state.displayPrefs.waterCardEnabled,
                    onCheckedChange = viewModel::setWaterCardEnabled,
                    icon = HugeIcons.Droplet,
                )
            }
            SettingsSection(
                title = "开水点击方式",
                subtitle = "对今日页开水卡与开水页的「开水」大按钮生效。",
            ) {
                SettingChoiceRow(
                    title = "点击方式",
                    subtitle = "双击确认防误触",
                    icon = HugeIcons.GlassWater,
                    options = listOf("单击", "双击"),
                    selectedIndex = if (state.displayPrefs.waterRequireDoubleClick) 1 else 0,
                    onSelect = { index ->
                        haptics.toggle()
                        viewModel.setWaterRequireDoubleClick(index == 1)
                    },
                )
            }
        }
    }
}

package edu.jxslu.schedule.ui.ebike

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ScooterElectric

/**
 * 共享单车出码页（DESIGN §3.9）：尾部车号输入 → 生成骑行二维码 →
 * 自动保存（开关默认关）/手动保存 + 扫完即焚（开关默认开：回到 App 即清除
 * 已保存的码）+ 最近车号回填 + 微信扫一扫 best-effort。
 * 结果提示走页面 Snackbar（二级页窗口内无更高层弹层，不会穿透问题）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EbikeQrScreen(
    onBack: () -> Unit = {},
    viewModel: EbikeViewModel = viewModel(
        factory = EbikeViewModel.Factory(Graph.displayPrefs(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs by viewModel.ebikePrefs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is EbikeEvent.Notice -> snackbar.showSnackbar(
                    AppNoticeVisuals(event.text, tone = event.tone),
                )
            }
        }
    }

    // 扫完即焚触发点（DESIGN §3.9）：从微信/桌面回到 App（ON_RESUME）时清掉
    // 已保存的二维码。 DisposableEffect 组合提交晚于 ON_RESUME 的场景（冷启动恢复）
    // 用 isAtLeast(RESUMED) 兜底执行一次；pending 为空时 burnPending 是 no-op，天然幂等。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.burnPending()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.burnPending()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("快趣出行码") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 输入区：尾部 3 位车号；模板前缀在提示文案里交代，输入框只收尾部
            OutlinedTextField(
                value = state.tailInput,
                onValueChange = viewModel::onTailInput,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("车身号后三位") },
                placeholder = { Text("如 669") },
                supportingText = { Text("完整车号 = 100000 + 尾部三位（如 100000669）") },
                isError = state.inputError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
            )
            // 输入校验行内提示；非法车号不发事件，就地展示
            state.inputError?.let { error ->
                InlineNoticeRow(message = error, tone = NoticeTone.Warning)
            }

            Button(
                onClick = {
                    haptics.tap()
                    keyboard?.hide()
                    focusManager.clearFocus()
                    viewModel.generate()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("生成二维码")
            }

            // 自动保存开关（DESIGN §3.9：默认关，相册只留用户真的要的码）
            var autoSaveChecked by remember(prefs.autoSave) { mutableStateOf(prefs.autoSave) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.tap()
                        autoSaveChecked = !autoSaveChecked
                        scope.launch { Graph.displayPrefs(context).setEbikeAutoSave(autoSaveChecked) }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "生成后自动保存到相册",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "开启后每次出码即存入相册「水贝贝」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
                Switch(
                    checked = autoSaveChecked,
                    onCheckedChange = {
                        haptics.tap()
                        autoSaveChecked = it
                        scope.launch { Graph.displayPrefs(context).setEbikeAutoSave(it) }
                    },
                )
            }

            // 扫完即焚开关（DESIGN §3.9：默认开——保存的码是扫码一次性耗材，用完不留痕）
            var burnChecked by remember(prefs.burnAfterScan) { mutableStateOf(prefs.burnAfterScan) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.tap()
                        burnChecked = !burnChecked
                        scope.launch { Graph.displayPrefs(context).setEbikeBurnAfterScan(burnChecked) }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "扫完码返回后自动删除",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "保存到相册的二维码会在回到 App 后自动清除",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
                Switch(
                    checked = burnChecked,
                    onCheckedChange = {
                        haptics.tap()
                        burnChecked = it
                        scope.launch { Graph.displayPrefs(context).setEbikeBurnAfterScan(it) }
                    },
                )
            }

            // 码展示区：有码出大图 + 操作行，无码给占位说明
            val bitmap = state.generatedBitmap
            if (bitmap != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "骑行二维码 · ${state.generatedBikeId}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp)),
                    )
                    Text(
                        text = "车号 ${state.generatedBikeId} · 微信「扫一扫」对准二维码即可开车",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            haptics.tap()
                            viewModel.saveCurrent()
                        }) {
                            Text("保存到相册")
                        }
                        Button(onClick = {
                            haptics.tap()
                            openWechatScan(context) { message ->
                                scope.launch {
                                    snackbar.showSnackbar(
                                        AppNoticeVisuals(message, tone = NoticeTone.Warning),
                                    )
                                }
                            }
                        }) {
                            Text("打开微信扫一扫")
                        }
                    }
                }
            } else {
                PlaceholderHint()
            }

            // 最近车号（DESIGN §3.9：8 个，点击回填）
            if (prefs.recentIds.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "最近生成",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        prefs.recentIds.take(4).forEach { tail ->
                            RecentChip(tail) {
                                haptics.tap()
                                viewModel.onPickRecent(tail)
                            }
                        }
                    }
                    if (prefs.recentIds.size > 4) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            prefs.recentIds.drop(4).take(4).forEach { tail ->
                                RecentChip(tail) {
                                    haptics.tap()
                                    viewModel.onPickRecent(tail)
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = "非学校官方功能：二维码内容为共享电单车运营方链接，最终以小程序加载结果为准。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun RecentChip(tail: String, onClick: () -> Unit) {
    Text(
        text = "…$tail",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun PlaceholderHint() {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            HugeIcons.ScooterElectric,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "输入车身号后三位生成骑行二维码，\n微信「扫一扫」扫码即可解锁对应车辆。",
            style = MaterialTheme.typography.bodySmall,
            color = onSurface.copy(alpha = 0.6f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 拉起微信「扫一扫」。入口按可靠性排序：
 * 1. `ShortCutDispatchAction` + `launch_type_scan_qrcode`——微信桌面长按「扫一扫」
 *    快捷方式的真身（`dumpsys shortcut com.tencent.mm` 实测），直达扫一扫相机页；
 * 2. `BIZSHORTCUT` + `LauncherUI.From.Scaner.Shortcut`——旧式快捷入口，部分版本
 *    只落微信首页（真机实测），仅作兜底；
 * 3. 打开微信首页给手动引导——出码本身已成功，这一步只是省一次手动切 App。
 */
private fun openWechatScan(context: android.content.Context, onError: (String) -> Unit) {
    val dispatchScan = Intent("com.tencent.mm.ui.ShortCutDispatchAction")
        .setPackage("com.tencent.mm")
        .putExtra("LauncherUI.Shortcut.LaunchType", "launch_type_scan_qrcode")
    try {
        context.startActivity(dispatchScan)
        return
    } catch (_: Exception) {
        // 落到下一级
    }
    val bizShortcut = Intent("com.tencent.mm.action.BIZSHORTCUT")
        .setPackage("com.tencent.mm")
        .addFlags(0x14000000) // NEW_TASK | CLEAR_TOP（沿用微信 shortcut 的 launchFlags）
        .putExtra("LauncherUI.From.Scaner.Shortcut", true)
    try {
        context.startActivity(bizShortcut)
        return
    } catch (_: Exception) {
        // 落到手动引导
    }
    try {
        context.startActivity(
            context.packageManager.getLaunchIntentForPackage("com.tencent.mm"),
        )
        onError("微信已打开，请在「发现 → 扫一扫」对准二维码")
    } catch (e2: Exception) {
        onError("无法自动打开微信，请手动打开「扫一扫」扫码")
    }
}

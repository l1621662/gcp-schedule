package edu.jxslu.schedule.ui.campus

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.CreditCard

/**
 * 校园卡付款码页（DESIGN §3.10）：进页自动取码，大 QR + Code128 双展示。
 *
 * **防截屏**：窗口加 `FLAG_SECURE`（截屏/录屏/最近任务缩略图全黑）——付款码等同现金，
 * 网页端 H5 同样防截屏；离开页面即清除。**亮度拉满**：食堂/超市扫码枪对亮度敏感，
 * 进页把 `screenBrightness` 顶到 1f，离开恢复原值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayCodeScreen(
    onBack: () -> Unit = {},
    /** 跳消费流水页（DESIGN §4.19 B4） */
    onOpenStatement: () -> Unit = {},
    viewModel: PayCodeViewModel = viewModel(factory = PayCodeViewModel.Factory(LocalContext.current)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val bitmaps by viewModel.bitmaps.collectAsStateWithLifecycle()
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()

    // 进页自动取码
    LaunchedEffect(Unit) { viewModel.load() }

    // 事件出口（换批失败等）
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PayCodeEvent.Notice ->
                    snackbar.showSnackbar(AppNoticeVisuals(event.text, tone = event.tone))
            }
        }
    }

    // FLAG_SECURE + 亮度拉满：离开即恢复（两段各管各的窗口属性）
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val previousBrightness =
            window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            }
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = previousBrightness }
            }
        }
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("水宝宝一卡通") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val s = state) {
                is PayCodeUiState.Loading -> LoadingBody()
                is PayCodeUiState.Error -> ErrorBody(s, onRetry = {
                    haptics.tap()
                    viewModel.load(force = true)
                })
                is PayCodeUiState.Success -> SuccessBody(s, bitmaps, onNext = {
                    haptics.tap()
                    viewModel.next()
                })
            }

            // 余额行 + 流水入口（DESIGN §4.19 B3/B4）：取到才显示余额；流水入口恒在
            BalanceRow(balance, onOpenStatement = {
                haptics.tap()
                onOpenStatement()
            }, onRefresh = { viewModel.refreshBalance() })

            Text(
                text = "付款码等同现金，请勿截图或分享给他人。每个码仅可消费一次，" +
                    "用掉后服务端自动递补下一个。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 余额行：卡余额（+电费账户非零时附带）+ 「消费流水」入口 + 点击刷新余额。 */
@Composable
private fun BalanceRow(
    balance: PayCodeViewModel.BalanceSnapshot?,
    onOpenStatement: () -> Unit,
    onRefresh: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onRefresh)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (balance == null) {
                Text(
                    text = "余额获取中…（点击刷新）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            } else {
                Text(
                    text = "卡余额 ¥%.2f".format(balance.totalFen / 100.0) +
                        if (balance.elecFen > 0) " · 电费 ¥%.2f".format(balance.elecFen / 100.0) else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onOpenStatement) {
            Text("消费流水")
        }
    }
}

@Composable
private fun LoadingBody() {
    LoadingHint(
        "正在登录校园卡",
        subtitle = "连接水宝宝并获取付款码…",
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
    )
}

@Composable
private fun ErrorBody(state: PayCodeUiState.Error, onRetry: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            HugeIcons.CreditCard,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Text(
            state.message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (state.canRetry) {
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun SuccessBody(
    state: PayCodeUiState.Success,
    bitmaps: PayCodeBitmaps?,
    onNext: () -> Unit,
) {
    if (bitmaps == null) {
        // 码位图尚未渲染完成（Bitmap 生成在 IO 线程）：水滴呼吸占位，与页内其余加载态同语言
        LoadingHint(
            "正在生成付款码",
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp),
        )
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            bitmap = bitmaps.qr.asImageBitmap(),
            contentDescription = "校园卡付款码二维码",
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp)),
        )
        Image(
            bitmap = bitmaps.barcode.asImageBitmap(),
            contentDescription = "校园卡付款码条形码",
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
        )
        Text(
            text = "${state.accountMasked} · 第 ${state.index + 1}/${state.codes.size} 个 · " +
                "约 ${state.expiresSeconds / 3600} 小时内有效",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
        )
        Text(
            text = state.current,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
        )
        if (state.hasMore) {
            OutlinedButton(onClick = onNext) { Text("下一个码") }
        }
    }
}

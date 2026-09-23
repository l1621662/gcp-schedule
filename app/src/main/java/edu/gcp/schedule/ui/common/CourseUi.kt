package edu.gcp.schedule.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.gcp.schedule.domain.Course
import edu.gcp.schedule.domain.CourseKind
import edu.gcp.schedule.domain.compactPosition
import edu.gcp.schedule.domain.dayLabel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ClipboardPen
import me.rerere.hugeicons.stroke.FlaskRound

/**
 * 课程调色板：中饱和粉彩，实色块配白字。
 * 条数必须与 [edu.gcp.schedule.domain.ScheduleCalculator.PALETTE_SIZE] 一致。
 * 12→16：理论 + 实验课的不同课名常超 12 个，12 桶取模回绕必然撞色。
 * 新色**追加在尾部**：下标 0–11 的含义不变，已入库课程的色值不受影响。
 */
val coursePalette: List<Color> = listOf(
    Color(0xFF6AAF8F),
    Color(0xFFE5A06A),
    Color(0xFFDE7B7B),
    Color(0xFF6F9ED6),
    Color(0xFFD080A0),
    Color(0xFF9480D0),
    Color(0xFFD4B050),
    Color(0xFF5FAEB8),
    Color(0xFF7A9EDE),
    Color(0xFF84B878),
    Color(0xFFF09070),
    Color(0xFF62B8B0),
    // —— 以下 4 色为扩容追加（12–15），与既有 12 色拉开色相/明度距离
    Color(0xFFB08968), // 棕褐
    Color(0xFF9FAF5F), // 橄榄黄绿
    Color(0xFFBE6FB0), // 洋红
    Color(0xFF90A4AE), // 蓝灰
)

fun courseColor(colorIndex: Int): Color =
    coursePalette[((colorIndex % coursePalette.size) + coursePalette.size) % coursePalette.size]

/**
 * 网格色块的样式参数（来自「显示设置」，由 WeekScreen 组装后传给各卡片）。
 * 默认值即 WakeUp 基准：6dp 圆角、全不透明、左对齐顶部起排、显示教师、显示虚线描边。
 *
 * [roomFontSp] / [teacherFontSp]：教室/教师行的**实际渲染 sp**，由 WeekScreen 按
 * 「用户目标 dp ÷ 网格密度倍率」解析后传入（网格整体还套着一层 fontScale，预除一次
 * 才能让净渲染值等于目标 dp）。null = 未单独设置，跟随课名倍率等比缩放（旧行为）。
 */
data class GridCellStyle(
    val cornerRadiusDp: Float = 6f,
    val opacity: Float = 1f,
    val centerHorizontal: Boolean = false,
    val centerVertical: Boolean = false,
    val showTeacher: Boolean = true,
    val showBorder: Boolean = true,
    val roomFontSp: Float? = null,
    val teacherFontSp: Float? = null,
    /** 地点前是否带「@」前缀。关掉可省一格宽度（7 列窄列里 @ 之后往往刚好挤掉一个字）。 */
    val showAtSign: Boolean = true,
)

/**
 * WakeUp 式课程色块虚线描边。
 * 必须挂在 `background` 之后（Modifier 顺序即绘制顺序，后画的在上层），
 * 内容（文字）仍在其上。内缩半个线宽，避免描边一半落在 clip 边界外被裁掉。
 * 圆角半径与色块本体一致，避免描边和底色错位。
 * [dark] 下收敛密度与透明度：白 0.85 的虚线在深底上会炸成一圈亮刺，降为 0.30 + 短虚线段。
 */
private fun Modifier.courseDashedBorder(
    cornerRadiusDp: Float,
    dark: Boolean,
): Modifier = drawBehind {
    val strokePx = 1.dp.toPx()
    val inset = strokePx / 2f
    val dashPx = if (dark) 3.dp.toPx() else 5.dp.toPx()
    val gapPx = if (dark) 3.dp.toPx() else 4.dp.toPx()
    // 根因：圆角半径内缩半线宽后，用户选 0 / 0.5dp 时会算出负值，负半径进 Skia 行为不可靠，夹到 0
    val radiusPx = (cornerRadiusDp.dp.toPx() - inset).coerceAtLeast(0f)
    drawRoundRect(
        color = Color.White.copy(alpha = if (dark) 0.30f else 0.85f),
        topLeft = Offset(inset, inset),
        size = Size(size.width - strokePx, size.height - strokePx),
        cornerRadius = CornerRadius(radiusPx),
        style = Stroke(
            width = strokePx,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx)),
        ),
    )
}

/**
 * 考试卡的白色**实线**描边（DESIGN §4.14）：与普通课的虚线拉开形状差异，
 * 用户先靠「实线 vs 虚线」扫出异常格，再靠徽章图标确认是考试。
 * 密度收敛策略与 [courseDashedBorder] 相同：深色下降透明度避免亮刺。
 */
private fun Modifier.courseSolidBorder(
    cornerRadiusDp: Float,
    dark: Boolean,
): Modifier = drawBehind {
    val strokePx = 1.dp.toPx()
    val inset = strokePx / 2f
    val radiusPx = (cornerRadiusDp.dp.toPx() - inset).coerceAtLeast(0f)
    drawRoundRect(
        color = Color.White.copy(alpha = if (dark) 0.45f else 0.95f),
        topLeft = Offset(inset, inset),
        size = Size(size.width - strokePx, size.height - strokePx),
        cornerRadius = CornerRadius(radiusPx),
        style = Stroke(width = strokePx),
    )
}

/**
 * 类型图标（DESIGN §4.14）：实验 = 烧瓶、考试 = 试卷笔；理论课没有徽章。
 * 图标名来自本地 JAR 检索（find-hugeicons），勿凭记忆改名。
 */
private fun kindIcon(kind: CourseKind): ImageVector? = when (kind) {
    CourseKind.Theory -> null
    CourseKind.Lab -> HugeIcons.FlaskRound
    CourseKind.Exam -> HugeIcons.ClipboardPen
}

/**
 * 类型徽章：圆形半透明白底 + 深色线性图标，取代旧版「右下角两个小字」——
 * 两个字在色块海里存在感太弱；圆底反差先被扫到，图标再确认类型。
 * [diameter] 由调用方按卡片尺寸给：网格卡 14dp、单节小卡 11dp。
 */
@Composable
private fun KindBadge(
    kind: CourseKind,
    diameter: Dp,
    modifier: Modifier = Modifier,
) {
    val icon = kindIcon(kind) ?: return
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = kind.label,
            tint = Color.Black.copy(alpha = 0.66f),
            modifier = Modifier.size(diameter * 0.62f),
        )
    }
}

/**
 * 周课网格里的课程色块。
 *
 * 视觉基准对齐 WakeUp：色块几乎填满格子（四周只留 1dp，由调用方的 inset 决定）、
 * 小圆角、白色虚线描边；内容**顶部起排**（课名 → @地点），教师沉到块底部——
 * 之前全部居中让多行课名的块上下留白不均，且教师位置随内容漂移，扫读时没有固定锚点。
 * 居中方式与教师显隐由 [GridCellStyle] 控制（显示设置里可调）。
 *
 * 类型区分（实验/考试）：右下角图标徽章；考试卡另加白色**实线**描边，
 * 与普通课的虚线描边拉开形状差异。颜色仍按课程名分配，不挪用类型语义。
 *
 * [days] 决定字号：7 天时单列内容宽约 40dp，中文一行 3 字，超出会被截断，因此课名压到 11sp；
 * 5 天模式单列约 70dp，可以放宽到 12.5sp（见 DESIGN 3.2）。
 */
@Composable
fun GridCourseCard(
    course: Course,
    days: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: GridCellStyle = GridCellStyle(),
) {
    val compact = days > 5
    val nameSize = if (compact) 11.sp else 12.5.sp
    val metaSize = if (compact) 9.sp else 10.5.sp
    // 教室/教师：用户单独设置过就用解析好的 sp，否则回落到 meta 档（跟随课名倍率）
    val roomSize = style.roomFontSp?.sp ?: metaSize
    val teacherSize = style.teacherFontSp?.sp ?: metaSize
    val accent = courseColor(course.colorIndex)
    val location = compactPosition(course.position)
    val align = if (style.centerHorizontal) TextAlign.Center else TextAlign.Start
    val columnAlign = if (style.centerHorizontal) Alignment.CenterHorizontally else Alignment.Start
    // surface 明度区分深浅色：深色下描边收敛，避免白虚线扎眼
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val hasBadge = course.kind != CourseKind.Theory

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(style.cornerRadiusDp.dp))
            .background(accent.copy(alpha = style.opacity))
            .then(
                when {
                    // 考试用实线描边：先靠形状差异从色块海里扫出来
                    course.kind == CourseKind.Exam ->
                        Modifier.courseSolidBorder(style.cornerRadiusDp, isDark)
                    style.showBorder -> Modifier.courseDashedBorder(style.cornerRadiusDp, isDark)
                    else -> Modifier
                },
            )
            .clickable(onClickLabel = "查看课程详情", onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 3.dp, vertical = 2.dp),
            verticalArrangement = if (style.centerVertical) Arrangement.Center else Arrangement.Top,
            horizontalAlignment = columnAlign,
        ) {
            Text(
                text = course.name,
                style = TextStyle(
                    fontSize = nameSize,
                    lineHeight = nameSize * 1.2f,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = Color.White,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                textAlign = align,
            )
            if (location.isNotBlank()) {
                Text(
                    text = if (style.showAtSign) "@$location" else location,
                    style = TextStyle(fontSize = roomSize, lineHeight = roomSize * 1.15f),
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = align,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            if (style.showTeacher && course.teacher.isNotBlank()) {
                // 竖直居中时教师跟在地点后面；否则沉到块底，位置有固定锚点
                if (!style.centerVertical) {
                    Spacer(Modifier.weight(1f))
                } else {
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    text = course.teacher,
                    style = TextStyle(fontSize = teacherSize, lineHeight = teacherSize * 1.15f),
                    color = Color.White.copy(alpha = 0.74f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 类型徽章占据右下角，教师名预先让位，避免长教师名压到徽章底下
                    modifier = Modifier.padding(end = if (hasBadge) 18.dp else 0.dp),
                )
            }
        }
        if (hasBadge) {
            KindBadge(
                kind = course.kind,
                diameter = 14.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 2.dp, bottom = 2.dp),
            )
        }
    }
}

/** 非本周课程灰态：只出现在空闲格子里，用来回答「这个时段本来有没有课」。 */
@Composable
fun GhostCourseCard(
    name: String,
    days: Int,
    modifier: Modifier = Modifier,
    cornerRadiusDp: Float = 6f,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val textSize = if (days > 5) 10.sp else 11.5.sp
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadiusDp.dp))
            .background(onSurface.copy(alpha = 0.035f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            style = TextStyle(fontSize = textSize, lineHeight = textSize * 1.2f),
            color = onSurface.copy(alpha = 0.30f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp),
        )
    }
}

/** 单小节课块：高度只够放课程名，居中显示。圆角/不透明度/水平对齐跟随 [GridCellStyle]。 */
@Composable
fun SingleSectionCard(
    course: Course,
    days: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: GridCellStyle = GridCellStyle(),
) {
    val accent = courseColor(course.colorIndex)
    val textSize = if (days > 5) 10.sp else 11.5.sp
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(style.cornerRadiusDp.dp))
            .background(accent.copy(alpha = style.opacity))
            .then(
                when {
                    course.kind == CourseKind.Exam ->
                        Modifier.courseSolidBorder(style.cornerRadiusDp, isDark)
                    style.showBorder -> Modifier.courseDashedBorder(style.cornerRadiusDp, isDark)
                    else -> Modifier
                },
            )
            .clickable(onClickLabel = "查看课程详情", onClick = onClick),
        contentAlignment = if (style.centerHorizontal) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(
            text = course.name,
            style = TextStyle(
                fontSize = textSize,
                lineHeight = textSize * 1.2f,
                fontWeight = FontWeight.SemiBold,
            ),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (style.centerHorizontal) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        // 单节课块（约 40–53dp）放不下 14dp 徽章，缩到 11dp 挤右上角；
        // 完整类型在点开后的课程详情里给
        if (course.kind != CourseKind.Theory) {
            KindBadge(
                kind = course.kind,
                diameter = 11.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp),
            )
        }
    }
}

/**
 * 课程信息表单。星期/节次是**滚轮选择**而不是裸数字输入框：
 * 「星期填 9」「节次填 99」这类脏输入在数字框里畅通无阻，滚轮从根上取缔；
 * 课程名错误态由 [nameError] 驱动（空名保存时行内提示，不再静默无反应）。
 */
@Composable
fun CourseEditorFields(
    name: String,
    onName: (String) -> Unit,
    teacher: String,
    onTeacher: (String) -> Unit,
    position: String,
    onPosition: (String) -> Unit,
    day: Int,
    onDay: (Int) -> Unit,
    startSection: Int,
    onStart: (Int) -> Unit,
    endSection: Int,
    onEnd: (Int) -> Unit,
    weeksText: String,
    onWeeks: (String) -> Unit,
    maxSection: Int = 11,
    nameError: String? = null,
) {
    var dayPickerOpen by remember { mutableStateOf(false) }
    var startPickerOpen by remember { mutableStateOf(false) }
    var endPickerOpen by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = name,
        // 错误态的清除由父级在 onName 里做（父级持有 name 与 error 两个状态）
        onValueChange = onName,
        label = { Text("课程名称") },
        isError = nameError != null,
        supportingText = { nameError?.let { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = teacher,
        onValueChange = onTeacher,
        label = { Text("教师") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = position,
        onValueChange = onPosition,
        label = { Text("地点") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectorField(
            label = "星期",
            value = dayLabel(day),
            onClick = { dayPickerOpen = true },
            modifier = Modifier.weight(1f),
        )
        SelectorField(
            label = "起始节",
            value = "$startSection",
            onClick = { startPickerOpen = true },
            modifier = Modifier.weight(1f),
        )
        SelectorField(
            label = "结束节",
            value = "$endSection",
            onClick = { endPickerOpen = true },
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        text = "节次按小节选（上午第 2 节就选 2，晚自习是 9–11）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 6.dp),
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = weeksText,
        onValueChange = onWeeks,
        label = { Text("周次，如 1-16 或 1,3,5") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = { onWeeks("1-16") }) { Text("1-16周") }
        TextButton(onClick = { onWeeks(oddWeeks()) }) { Text("单周") }
        TextButton(onClick = { onWeeks(evenWeeks()) }) { Text("双周") }
    }

    if (dayPickerOpen) {
        WheelValueDialog(
            title = "星期",
            values = (1..7).map { dayLabel(it) },
            initialIndex = (day - 1).coerceIn(0, 6),
            onConfirm = { index ->
                dayPickerOpen = false
                onDay(index + 1)
            },
            onDismiss = { dayPickerOpen = false },
        )
    }
    if (startPickerOpen) {
        WheelValueDialog(
            title = "起始节",
            values = (1..maxSection).map { "第 $it 节" },
            initialIndex = (startSection - 1).coerceIn(0, maxSection - 1),
            onConfirm = { index ->
                startPickerOpen = false
                onStart(index + 1)
            },
            onDismiss = { startPickerOpen = false },
        )
    }
    if (endPickerOpen) {
        WheelValueDialog(
            title = "结束节",
            values = (startSection..maxSection).map { "第 $it 节" },
            initialIndex = (endSection - startSection).coerceIn(0, maxSection - startSection),
            onConfirm = { index ->
                endPickerOpen = false
                onEnd(index + startSection)
            },
            onDismiss = { endPickerOpen = false },
        )
    }
}

/** 滚轮选择入口：仿 OutlinedTextField 的边框 + 上浮小标签，整块可点、无键盘。 */
@Composable
private fun SelectorField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberAppHaptics()
    Column(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
        )
    }
}

fun oddWeeks(): String = (1..16 step 2).joinToString(",")
fun evenWeeks(): String = (2..16 step 2).joinToString(",")

fun parseWeeksInput(text: String): Set<Int> {
    val result = mutableSetOf<Int>()
    for (part in text.split(',', '，')) {
        val p = part.trim()
        if (p.isEmpty()) continue
        val range = p.split('-', '—', '~')
        if (range.size == 2) {
            val a = range[0].trim().toIntOrNull()
            val b = range[1].trim().toIntOrNull()
            if (a != null && b != null && a in 1..40 && b in a..40) {
                result += (a..b)
            }
        } else {
            p.toIntOrNull()?.let { if (it in 1..40) result += it }
        }
    }
    return result
}

fun weeksToInput(weeks: Set<Int>): String = weeks.sorted().joinToString(",")

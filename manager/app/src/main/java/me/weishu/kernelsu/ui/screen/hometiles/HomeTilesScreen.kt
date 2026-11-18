package me.weishu.kernelsu.ui.screen.hometiles

import android.content.Context
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.repository.ModuleRepositoryImpl
import me.weishu.kernelsu.data.theme.ThemeRepository
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.viewmodel.HomeViewModel
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 磁吸磁贴开始屏幕（参考 Magisk-Metro 的 Metro 磁贴板）。
 *
 * 布局引擎不是"手摆位置"，而是**网格 + 打包**：
 *  * 4 列基底网格，磁贴以 (col,row,spanW,spanH) 描述；
 *  * 长按任意磁贴进入编辑，拖动时磁贴跟手、松手按最近的**空闲**格吸过去（磁吸）；
 *  * 编辑态右下角手柄拖动缩放（1×1 / 2×1 / 1×2 / 2×2），越界或撞车自动回收；
 *  * 位置写进 SharedPreferences，重启后原样恢复；
 *  * 所有落位都走 spring 动画，邻居不会"跳格"。
 */
@Composable
fun HomeTilesScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val homeViewModel = viewModel<HomeViewModel>()
    val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { homeViewModel.refresh() }

    val moduleCount by produceState<Int?>(initialValue = null, homeState.ksuVersion) {
        value = withContext(Dispatchers.IO) {
            runCatching { ModuleRepositoryImpl().getModules().getOrNull()?.size }.getOrNull()
        }
    }
    val superuserCount by produceState(initialValue = 0, homeState.ksuVersion) {
        value = withContext(Dispatchers.IO) { runCatching { Natives.getSuperuserCount() }.getOrDefault(0) }
    }
    val themeName by produceState<String?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ThemeRepository.activeId(context)?.let { id -> ThemeRepository.find(context, id)?.spec?.name }
            }.getOrNull()
        }
    }

    val tiles = remember(homeState, moduleCount, superuserCount, themeName) {
        listOf(
            MetroTile(
                id = "kernel",
                role = MetroRole.Kernel,
                icon = Icons.Rounded.Memory,
                title = "内核",
                value = if (homeState.kernelVersion.major >= 0) homeState.kernelVersion.toString() else "—",
                caption = if (homeState.ksuVersion != null) "KernelSU 已连接" else "KernelSU 未连接",
            ),
            MetroTile(
                id = "manager",
                role = MetroRole.Manager,
                icon = Icons.Rounded.Speed,
                title = "管理器",
                value = BuildConfig.VERSION_NAME,
                caption = "XECKernel Ultra",
            ),
            MetroTile(
                id = "modules",
                role = MetroRole.Modules,
                icon = Icons.Rounded.Extension,
                title = "模块",
                value = moduleCount?.toString() ?: "…",
                caption = "已安装模块",
            ),
            MetroTile(
                id = "superuser",
                role = MetroRole.SuperUser,
                icon = Icons.Rounded.Security,
                title = "授权",
                value = superuserCount.toString(),
                caption = "已授权应用",
            ),
            MetroTile(
                id = "theme",
                role = MetroRole.Theme,
                icon = Icons.Rounded.Palette,
                title = "主题",
                value = themeName ?: "默认",
                caption = "当前外观",
            ),
            MetroTile(
                id = "tools",
                role = MetroRole.Tools,
                icon = Icons.Rounded.Straighten,
                title = "工具箱",
                value = if (homeState.isRootAvailable) "正常" else "检查中",
                caption = "评分 / 密钥池 / SUSFS",
            ),
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                title = "磁贴开始屏幕",
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        val layoutDirection = LocalLayoutDirection.current
                        Icon(
                            modifier = Modifier.graphicsLayerFlip(layoutDirection),
                            imageVector = MiuixIcons.Back,
                            contentDescription = null,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = innerPadding,
        ) {
            item {
                Column(modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)) {
                    Text(
                        text = "长按磁贴进入编辑；拖动时磁贴跟手，松手按最近的空格吸过去。" +
                            "编辑态右下角手柄可缩放，位置会记住。",
                        fontSize = 12.sp,
                        color = Xc.colors.textSecondary,
                    )
                }
            }
            item {
                MetroTileBoard(
                    tiles = tiles,
                    onOpen = { id ->
                        when (id) {
                            "modules" -> navigator.push(Route.ModuleRepo)
                            "theme" -> navigator.push(Route.Themes)
                            "tools" -> navigator.push(Route.Tools)
                            else -> Unit
                        }
                    },
                )
            }
            item {
                Text(
                    text = "磁贴板是「开始屏幕」的第一版：先验证网格、磁吸、缩放与持久化，" +
                        "下一版把它接成真正的首页。",
                    fontSize = 11.sp,
                    color = Xc.colors.textMuted,
                    modifier = Modifier.padding(top = 14.dp, bottom = 40.dp),
                )
            }
        }
    }
}

private fun Modifier.graphicsLayerFlip(layoutDirection: LayoutDirection): Modifier =
    this.then(
        Modifier.graphicsLayer {
            if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
        },
    )

// ------------------------------------------------------------------ 数据

private enum class MetroRole(val color: Color) {
    Kernel(Color(0xFF2E7D32)),
    Manager(Color(0xFF1565C0)),
    Modules(Color(0xFFB71C1C)),
    SuperUser(Color(0xFFF9A825)),
    Theme(Color(0xFF6A1B9A)),
    Tools(Color(0xFF00695C)),
}

private data class MetroTile(
    val id: String,
    val role: MetroRole,
    val icon: ImageVector,
    val title: String,
    val value: String,
    val caption: String,
)

private data class TilePlacement(
    val id: String,
    val col: Int,
    val row: Int,
    val spanW: Int,
    val spanH: Int,
)

private object TileLayoutStore {
    private const val PREFS = "settings"
    private const val KEY = "home_tile_layout_v2"
    private const val COLS = 4

    val defaults = listOf(
        TilePlacement("kernel", 0, 0, 2, 1),
        TilePlacement("manager", 2, 0, 2, 1),
        TilePlacement("modules", 0, 1, 2, 2),
        TilePlacement("superuser", 2, 1, 1, 1),
        TilePlacement("theme", 3, 1, 1, 1),
        TilePlacement("tools", 2, 2, 2, 1),
    )

    fun load(context: Context): List<TilePlacement> {
        val raw = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        }.getOrDefault("")
        if (raw.isEmpty()) return defaults
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id", "")
                if (!defaults.any { it.id == id }) return@mapNotNull null
                TilePlacement(
                    id = id,
                    col = item.optInt("col", 0).coerceIn(0, COLS - 1),
                    row = item.optInt("row", 0).coerceIn(0, 64),
                    spanW = item.optInt("spanW", 1).coerceIn(1, COLS),
                    spanH = item.optInt("spanH", 1).coerceIn(1, 4),
                )
            }
        }.getOrNull()?.takeIf { it.size == defaults.size } ?: defaults
    }

    fun save(context: Context, placements: List<TilePlacement>) {
        val array = JSONArray()
        placements.forEach { placement ->
            array.put(
                JSONObject().apply {
                    put("id", placement.id)
                    put("col", placement.col)
                    put("row", placement.row)
                    put("spanW", placement.spanW)
                    put("spanH", placement.spanH)
                },
            )
        }
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, array.toString()).apply()
        }
    }
}

// ------------------------------------------------------------------ 磁贴板

private const val COLS = 4
private val GAP = 8.dp

@Composable
private fun MetroTileBoard(
    tiles: List<MetroTile>,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var placements by remember { mutableStateOf(TileLayoutStore.load(context)) }
    var editMode by remember { mutableStateOf(false) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragDelta by remember { mutableStateOf(IntOffset.Zero) }
    var resizingId by remember { mutableStateOf<String?>(null) }
    var resizeDelta by remember { mutableStateOf(IntOffset.Zero) }

    val currentPlacements = rememberUpdatedState(placements)
    val currentTiles = rememberUpdatedState(tiles)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gapPx = with(LocalDensity.current) { GAP.toPx() }
        val cellW = ((constraints.maxWidth - gapPx * (COLS + 1)) / COLS).coerceAtLeast(1f)
        val cellH = cellW * 0.92f
        val rows = (placements.maxOfOrNull { it.row + it.spanH } ?: 3).coerceAtLeast(3)
        val boardH = cellH * rows + gapPx * (rows + 1)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(LocalDensity.current) { boardH.toDp() }),
        ) {
            currentPlacements.value.forEach { placement ->
                val tile = currentTiles.value.firstOrNull { it.id == placement.id } ?: return@forEach
                val baseX = gapPx + placement.col * (cellW + gapPx)
                val baseY = gapPx + placement.row * (cellH + gapPx)
                val isDragging = draggingId == placement.id
                val isResizing = resizingId == placement.id
                val rawX = baseX + if (isDragging) dragDelta.x.toFloat() else 0f
                val rawY = baseY + if (isDragging) dragDelta.y.toFloat() else 0f
                val rawW = cellW * placement.spanW + gapPx * (placement.spanW - 1) +
                    if (isResizing) resizeDelta.x.toFloat() else 0f
                val rawH = cellH * placement.spanH + gapPx * (placement.spanH - 1) +
                    if (isResizing) resizeDelta.y.toFloat() else 0f
                val animated by animateIntOffsetAsState(
                    targetValue = IntOffset(rawX.roundToInt(), rawY.roundToInt()),
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 900f),
                    label = "tile-pos-${placement.id}",
                )
                MetroTileCard(
                    tile = tile,
                    widthPx = rawW.roundToInt().coerceAtLeast(1),
                    heightPx = rawH.roundToInt().coerceAtLeast(1),
                    offset = animated,
                    modifier = Modifier.zIndex(if (isDragging || isResizing) 2f else 1f),
                    editMode = editMode,
                    dragging = isDragging,
                    onTap = {
                        if (!editMode) onOpen(placement.id)
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        editMode = true
                    },
                    onDragStart = {
                        draggingId = placement.id
                        dragDelta = IntOffset.Zero
                    },
                    onDrag = { delta ->
                        dragDelta += delta
                    },
                    onDragEnd = {
                        val moved = snapPlacement(
                            list = currentPlacements.value,
                            movingId = placement.id,
                            deltaCol = (dragDelta.x / (cellW + gapPx)).roundToInt(),
                            deltaRow = (dragDelta.y / (cellH + gapPx)).roundToInt(),
                        )
                        placements = moved
                        TileLayoutStore.save(context, moved)
                        draggingId = null
                        dragDelta = IntOffset.Zero
                    },
                    onResizeStart = {
                        resizingId = placement.id
                        resizeDelta = IntOffset.Zero
                    },
                    onResize = { delta -> resizeDelta += delta },
                    onResizeEnd = {
                        val resized = resizePlacement(
                            list = currentPlacements.value,
                            movingId = placement.id,
                            addW = (resizeDelta.x / (cellW * 0.55f)).toInt(),
                            addH = (resizeDelta.y / (cellH * 0.55f)).toInt(),
                        )
                        placements = resized
                        TileLayoutStore.save(context, resized)
                        resizingId = null
                        resizeDelta = IntOffset.Zero
                    },
                )
            }
        }
    }

    if (editMode) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = "完成",
                onClick = { editMode = false },
                modifier = Modifier.padding(0.dp),
            )
            TextButton(
                text = "恢复默认布局",
                onClick = {
                    placements = TileLayoutStore.defaults
                    TileLayoutStore.save(context, placements)
                    editMode = false
                },
                modifier = Modifier.padding(0.dp),
            )
        }
    }
}

private fun snapPlacement(
    list: List<TilePlacement>,
    movingId: String,
    deltaCol: Int,
    deltaRow: Int,
): List<TilePlacement> {
    val moving = list.firstOrNull { it.id == movingId } ?: return list
    val others = list.filter { it.id != movingId }
    val spanW = moving.spanW.coerceAtMost(COLS)
    val targetCol = (moving.col + deltaCol).coerceIn(0, COLS - spanW)
    val maxRow = (others.maxOfOrNull { it.row + it.spanH } ?: 0).coerceAtLeast(0)
    val targetRow = (moving.row + deltaRow).coerceIn(0, maxRow)
    val maxSearch = 12
    val candidates = buildList {
        for (radius in 0..maxSearch) {
            for (dRow in -radius..radius) {
                for (dCol in -radius..radius) {
                    if (maxOf(abs(dRow), abs(dCol)) != radius) continue
                    add(targetCol + dCol to targetRow + dRow)
                }
            }
        }
    }
    val chosen = candidates
        .asSequence()
        .map { (col, row) -> Triple(col, row, col to row) }
        .filter { (col, row) -> col in 0..(COLS - spanW) && row >= 0 && row <= maxRow + 4 }
        .filter { (col, row) -> others.none { overlaps(it, col, row, spanW, moving.spanH) } }
        .minByOrNull { (col, row) -> abs(col - targetCol) * 2 + abs(row - targetRow) * 3 }
        ?.let { it.third }
    val finalCol = chosen?.first ?: moving.col
    val finalRow = chosen?.second ?: moving.row
    return list.map { if (it.id == movingId) it.copy(col = finalCol, row = finalRow) else it }
}

private fun resizePlacement(
    list: List<TilePlacement>,
    movingId: String,
    addW: Int,
    addH: Int,
): List<TilePlacement> {
    val moving = list.firstOrNull { it.id == movingId } ?: return list
    val others = list.filter { it.id != movingId }
    val newW = (moving.spanW + addW).coerceIn(1, 2)
    val newH = (moving.spanH + addH).coerceIn(1, 2)
    // 先看放大后的矩形是否和邻居打架；打架就退回原尺寸，不做"挤走邻居"的暴力重排。
    val w = newW.coerceAtMost(COLS - moving.col)
    val collides = others.any { overlaps(it, moving.col, moving.row, w, newH) }
    if (collides) return list
    return list.map { if (it.id == movingId) it.copy(spanW = w, spanH = newH) else it }
}

private fun overlaps(
    other: TilePlacement,
    col: Int,
    row: Int,
    spanW: Int,
    spanH: Int,
): Boolean = !(other.col + other.spanW <= col ||
    col + spanW <= other.col ||
    other.row + other.spanH <= row ||
    row + spanH <= other.row)

@Composable
private fun MetroTileCard(
    tile: MetroTile,
    widthPx: Int,
    heightPx: Int,
    offset: IntOffset,
    modifier: Modifier = Modifier,
    editMode: Boolean,
    dragging: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (IntOffset) -> Unit,
    onDragEnd: () -> Unit,
    onResizeStart: () -> Unit,
    onResize: (IntOffset) -> Unit,
    onResizeEnd: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .offset { offset }
            .size(
                width = with(LocalDensity.current) { widthPx.toFloat().toDp() },
                height = with(LocalDensity.current) { heightPx.toFloat().toDp() },
            )
            .background(
                color = if (editMode) tile.role.color.copy(alpha = 0.86f) else tile.role.color,
                shape = RoundedCornerShape(2.dp),
            )
            .border(
                width = if (editMode) 2.dp else 1.dp,
                color = if (editMode) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(2.dp),
            )
            .pointerInput(editMode, tile.id) {
                if (editMode) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDrag = { change, amount ->
                            change.consume()
                            onDrag(IntOffset(amount.x.roundToInt(), amount.y.roundToInt()))
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    )
                } else {
                    detectTapGestures(
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                        },
                        onTap = { onTap() },
                    )
                }
            },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = tile.icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "  " + tile.title,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.92f),
                )
            }
            Column {
                Text(
                    text = tile.value,
                    fontSize = 19.sp,
                    color = Color.White,
                    maxLines = 1,
                )
                Text(
                    text = tile.caption,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }
        }
        if (editMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(26.dp)
                    .pointerInput(tile.id) {
                        detectDragGestures(
                            onDragStart = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onResizeStart()
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                onResize(IntOffset(amount.x.roundToInt(), amount.y.roundToInt()))
                            },
                            onDragEnd = { onResizeEnd() },
                            onDragCancel = { onResizeEnd() },
                        )
                    },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(14.dp)
                        .padding(3.dp)
                        .background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

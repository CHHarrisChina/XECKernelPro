package me.weishu.kernelsu.ui.screen.hometiles

import android.content.Context
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.ViewAgenda
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.data.repository.ModuleRepositoryImpl
import me.weishu.kernelsu.data.theme.ThemeRepository
import me.weishu.kernelsu.ui.MainPagerBus
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.viewmodel.HomeViewModel
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 磁吸磁贴板（参考 Magisk-Metro 的 Metro 开始屏幕 + 布局编辑器）。
 *
 * 两个入口共用一套布局引擎：
 *  * [HomeTileBoardHome]：首页的磁贴视图（只读，点磁贴进对应页面；长按或铅笔进编辑器）；
 *  * [HomeTilesEditorScreen]：UI 编辑器（网格参考线、拖动磁吸、手柄缩放、删除、添加、换色、重置）。
 *
 * 布局是数据（`TilePlacement`），引擎负责打包与吸附：
 *  - 4 列网格；
 *  - 拖动时磁贴跟手，松手按最近的**空闲**格吸过去，绝不重叠；
 *  - 编辑态右下角手柄缩放 1×1 / 2×1 / 1×2 / 2×2，放不下就退回原尺寸；
 *  - 结果写进 SharedPreferences，重启后原样恢复。
 */
object HomeLayoutMode {
    private const val PREFS = "settings"
    private const val KEY = "home_layout_tiles"

    /** 默认磁贴：2026-11-18 起首页直接是磁贴板（用户要求把磁吸适配进 UI）。 */
    fun isTiles(context: Context): Boolean =
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true) }
            .getOrDefault(true)

    fun setTiles(context: Context, tiles: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, tiles).apply()
        }
    }
}

// ------------------------------------------------------------------ 首页磁贴视图

@Composable
fun HomeTileBoardHome(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean,
    onSwitchToCards: () -> Unit,
) {
    val context = LocalContext.current
    val tiles = rememberHomeTiles()
    var placements by remember { mutableStateOf(TileLayoutStore.load(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "开始屏幕", fontSize = 22.sp, color = Xc.colors.text)
                Text(
                    text = if (isCurrentPage) "长按磁贴进入布局编辑" else "",
                    fontSize = 11.sp,
                    color = Xc.colors.textMuted,
                )
            }
            IconButton(onClick = { navigator.push(Route.HomeTiles) }) {
                Icon(imageVector = Icons.Rounded.Edit, contentDescription = "编辑布局", tint = Xc.colors.textSecondary)
            }
            IconButton(onClick = onSwitchToCards) {
                Icon(
                    imageVector = Icons.Rounded.ViewAgenda,
                    contentDescription = "卡片视图",
                    tint = Xc.colors.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        MetroTileBoard(
            tiles = tiles,
            placements = placements,
            editable = false,
            showGuides = false,
            onPlacementsChange = { newPlacements ->
                placements = newPlacements
                TileLayoutStore.save(context, newPlacements)
            },
            onOpen = { id ->
                when (id) {
                    "modules" -> MainPagerBus.jumpTo(2)
                    "superuser" -> MainPagerBus.jumpTo(1)
                    "theme" -> navigator.push(Route.Themes)
                    "tools" -> navigator.push(Route.Tools)
                    else -> Unit
                }
            },
            onEditRequest = { navigator.push(Route.HomeTiles) },
        )
        Spacer(Modifier.height(bottomInnerPadding + 16.dp))
    }
}

// ------------------------------------------------------------------ UI 编辑器

@Composable
fun HomeTilesEditorScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    var roleTick by remember { mutableStateOf(0) }
    val catalog = rememberHomeTiles(roleTick)
    var placements by remember { mutableStateOf(TileLayoutStore.load(context)) }

    fun update(newPlacements: List<TilePlacement>) {
        placements = newPlacements
        TileLayoutStore.save(context, newPlacements)
    }

    val present = placements.map { it.id }.toSet()
    val missing = catalog.filter { it.id !in present }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navigator.pop() }) {
                Icon(imageVector = Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "磁贴布局编辑器", fontSize = 20.sp, color = Xc.colors.text)
                Text(
                    text = "拖动磁吸 · 手柄缩放 · × 删除 · 点磁贴换色",
                    fontSize = 11.sp,
                    color = Xc.colors.textMuted,
                )
            }
            TextButton(text = "完成", onClick = { navigator.pop() })
        }
        Spacer(Modifier.height(4.dp))
        MetroTileBoard(
            tiles = catalog,
            placements = placements,
            editable = true,
            showGuides = true,
            onPlacementsChange = ::update,
            onOpen = { id ->
                val currentRole = catalog.firstOrNull { it.id == id }?.role ?: MetroRole.Modules
                val order = listOf(
                    MetroRole.Kernel, MetroRole.Manager, MetroRole.Modules,
                    MetroRole.SuperUser, MetroRole.Theme, MetroRole.Tools,
                )
                val next = order[(order.indexOf(currentRole) + 1) % order.size]
                MetroRoleStore.set(context, id, next)
                roleTick++
            },
            onEditRequest = {},
            onRemove = { id -> update(placements.filterNot { it.id == id }) },
        )
        if (missing.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(text = "添加磁贴", fontSize = 13.sp, color = Xc.colors.textSecondary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                missing.forEach { tile ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(tile.role.color)
                            .clickable { update(appendPlacement(placements, tile.id)) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = tile.icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(text = tile.title, fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(
            text = "恢复默认布局",
            onClick = {
                MetroRoleStore.reset(context)
                update(TileLayoutStore.defaults)
                roleTick++
            },
        )
        Spacer(Modifier.height(40.dp))
    }
}

private fun appendPlacement(placements: List<TilePlacement>, id: String): List<TilePlacement> {
    val cols = TileLayoutStore.COLS
    val occupied = placements.flatMap { placement ->
        buildList {
            for (c in placement.col until placement.col + placement.spanW) {
                for (r in placement.row until placement.row + placement.spanH) add(c to r)
            }
        }
    }.toSet()
    val maxRow = (placements.maxOfOrNull { it.row + it.spanH } ?: 0).coerceAtLeast(0)
    for (row in 0..(maxRow + 6)) {
        for (col in 0 until cols) {
            if ((col to row) !in occupied) return placements + TilePlacement(id, col, row, 2, 1)
        }
    }
    return placements + TilePlacement(id, 0, maxRow, 2, 1)
}

// ------------------------------------------------------------------ 数据

@Composable
private fun rememberHomeTiles(roleTick: Int = 0): List<MetroTile> {
    val context = LocalContext.current
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
    val overrides = remember(roleTick) { MetroRoleStore.load(context) }

    return listOf(
        MetroTile(
            "kernel", overrides["kernel"] ?: MetroRole.Kernel, Icons.Rounded.Memory, "内核",
            if (homeState.kernelVersion.major >= 0) homeState.kernelVersion.toString() else "—",
            if (homeState.ksuVersion != null) "KernelSU 已连接" else "KernelSU 未连接",
        ),
        MetroTile(
            "manager", overrides["manager"] ?: MetroRole.Manager, Icons.Rounded.Speed, "管理器",
            BuildConfig.VERSION_NAME, "XECKernel Ultra",
        ),
        MetroTile(
            "modules", overrides["modules"] ?: MetroRole.Modules, Icons.Rounded.Extension, "模块",
            moduleCount?.toString() ?: "…", "已安装模块",
        ),
        MetroTile(
            "superuser", overrides["superuser"] ?: MetroRole.SuperUser, Icons.Rounded.Security, "授权",
            superuserCount.toString(), "已授权应用",
        ),
        MetroTile(
            "theme", overrides["theme"] ?: MetroRole.Theme, Icons.Rounded.Palette, "主题",
            themeName ?: "默认", "当前外观",
        ),
        MetroTile(
            "tools", overrides["tools"] ?: MetroRole.Tools, Icons.Rounded.Straighten, "工具箱",
            if (homeState.isRootAvailable) "正常" else "检查中", "评分 / 密钥池 / SUSFS",
        ),
    )
}

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

private object MetroRoleStore {
    private const val PREFS = "settings"
    private const val KEY = "home_tile_roles"

    fun load(context: Context): Map<String, MetroRole> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        if (raw.isEmpty()) return emptyMap()
        val json = JSONObject(raw)
        buildMap {
            json.keys().forEach { id ->
                runCatching { MetroRole.valueOf(json.getString(id)) }.getOrNull()?.let { put(id, it) }
            }
        }
    }.getOrDefault(emptyMap())

    fun set(context: Context, id: String, role: MetroRole) {
        val current = load(context).toMutableMap()
        current[id] = role
        val json = JSONObject()
        current.forEach { (key, value) -> json.put(key, value.name) }
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
        }
    }

    fun reset(context: Context) {
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply() }
    }
}

private object TileLayoutStore {
    private const val PREFS = "settings"
    private const val KEY = "home_tile_layout_v2"
    const val COLS = 4

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
                if (defaults.none { it.id == id }) return@mapNotNull null
                TilePlacement(
                    id = id,
                    col = item.optInt("col", 0).coerceIn(0, COLS - 1),
                    row = item.optInt("row", 0).coerceIn(0, 64),
                    spanW = item.optInt("spanW", 1).coerceIn(1, COLS),
                    spanH = item.optInt("spanH", 1).coerceIn(1, 4),
                )
            }
        }.getOrNull() ?: defaults
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

private val GAP = 8.dp

@Composable
private fun MetroTileBoard(
    tiles: List<MetroTile>,
    placements: List<TilePlacement>,
    editable: Boolean,
    showGuides: Boolean,
    onPlacementsChange: (List<TilePlacement>) -> Unit,
    onOpen: (String) -> Unit,
    onEditRequest: () -> Unit,
    onRemove: ((String) -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragDelta by remember { mutableStateOf(IntOffset.Zero) }
    var resizingId by remember { mutableStateOf<String?>(null) }
    var resizeDelta by remember { mutableStateOf(IntOffset.Zero) }
    val currentPlacements = rememberUpdatedState(placements)
    val currentTiles = rememberUpdatedState(tiles)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gapPx = with(LocalDensity.current) { GAP.toPx() }
        val cellW = ((constraints.maxWidth - gapPx * (TileLayoutStore.COLS + 1)) / TileLayoutStore.COLS)
            .coerceAtLeast(1f)
        val cellH = cellW * 0.92f
        val rows = (placements.maxOfOrNull { it.row + it.spanH } ?: 3).coerceAtLeast(3)
        val boardW = constraints.maxWidth.toFloat()
        val boardH = cellH * rows + gapPx * (rows + 1)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(LocalDensity.current) { (boardH + gapPx).toDp() }),
        ) {
            val guideRim = Xc.colors.glassRim
            if (showGuides) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val effect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
                    for (col in 1 until TileLayoutStore.COLS) {
                        val x = gapPx + col * (cellW + gapPx) - gapPx / 2
                        drawLine(
                            color = guideRim.copy(alpha = 0.55f),
                            start = Offset(x, 0f),
                            end = Offset(x, boardH),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = effect,
                        )
                    }
                    for (row in 1..rows) {
                        val y = gapPx + row * (cellH + gapPx) - gapPx / 2
                        drawLine(
                            color = guideRim.copy(alpha = 0.35f),
                            start = Offset(0f, y),
                            end = Offset(boardW, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = effect,
                        )
                    }
                }
            }

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
                    editable = editable,
                    onTap = {
                        if (editable) onOpen(placement.id) else onOpen(placement.id)
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (!editable) onEditRequest()
                    },
                    onDragStart = {
                        draggingId = placement.id
                        dragDelta = IntOffset.Zero
                    },
                    onDrag = { delta -> dragDelta += delta },
                    onDragEnd = {
                        val moved = snapPlacement(
                            list = currentPlacements.value,
                            movingId = placement.id,
                            deltaCol = (dragDelta.x / (cellW + gapPx)).roundToInt(),
                            deltaRow = (dragDelta.y / (cellH + gapPx)).roundToInt(),
                        )
                        onPlacementsChange(moved)
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
                        onPlacementsChange(resized)
                        resizingId = null
                        resizeDelta = IntOffset.Zero
                    },
                    onRemove = if (editable && onRemove != null) {
                        { onRemove(placement.id) }
                    } else {
                        null
                    },
                )
            }
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
    val cols = TileLayoutStore.COLS
    val spanW = moving.spanW.coerceAtMost(cols)
    val targetCol = (moving.col + deltaCol).coerceIn(0, cols - spanW)
    val maxRow = (others.maxOfOrNull { it.row + it.spanH } ?: 0).coerceAtLeast(0)
    val targetRow = (moving.row + deltaRow).coerceIn(0, maxRow)
    val candidates = buildList {
        for (radius in 0..12) {
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
        .filter { (col, row) -> col in 0..(cols - spanW) && row >= 0 && row <= maxRow + 4 }
        .filter { (col, row) -> others.none { overlaps(it, col, row, spanW, moving.spanH) } }
        .minByOrNull { (col, row) -> abs(col - targetCol) * 2 + abs(row - targetRow) * 3 }
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
    val w = newW.coerceAtMost(TileLayoutStore.COLS - moving.col)
    if (others.any { overlaps(it, moving.col, moving.row, w, newH) }) return list
    return list.map { if (it.id == movingId) it.copy(spanW = w, spanH = newH) else it }
}

private fun overlaps(other: TilePlacement, col: Int, row: Int, spanW: Int, spanH: Int): Boolean =
    !(other.col + other.spanW <= col ||
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
    editable: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (IntOffset) -> Unit,
    onDragEnd: () -> Unit,
    onResizeStart: () -> Unit,
    onResize: (IntOffset) -> Unit,
    onResizeEnd: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Box(
        modifier = modifier
            .offset { offset }
            .size(
                width = with(LocalDensity.current) { widthPx.toFloat().toDp() },
                height = with(LocalDensity.current) { heightPx.toFloat().toDp() },
            )
            .background(color = tile.role.color, shape = RoundedCornerShape(2.dp))
            .border(
                width = if (editable) 1.dp else 0.dp,
                color = Color.White.copy(alpha = 0.35f),
                shape = RoundedCornerShape(2.dp),
            )
            .pointerInput(editable, tile.id) {
                if (editable) {
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
                        onLongPress = { onLongPress() },
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
                Text(text = "  " + tile.title, fontSize = 11.sp, color = Color.White.copy(alpha = 0.92f))
            }
            Column {
                Text(text = tile.value, fontSize = 19.sp, color = Color.White, maxLines = 1)
                Text(text = tile.caption, fontSize = 10.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
            }
        }
        if (editable) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(26.dp)
                    .pointerInput(tile.id) {
                        detectDragGestures(
                            onDragStart = { onResizeStart() },
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
            if (onRemove != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp)
                        .clickable { onRemove() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "删除磁贴",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

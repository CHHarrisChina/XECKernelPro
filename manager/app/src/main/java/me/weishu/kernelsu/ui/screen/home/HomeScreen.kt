package me.weishu.kernelsu.ui.screen.home

import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import com.xecpro.kernel.XecMagicaService
import me.weishu.kernelsu.ui.component.dialog.rememberLoadingDialog
import me.weishu.kernelsu.ui.screen.hometiles.HomeLayoutMode
import me.weishu.kernelsu.ui.screen.hometiles.HomeTileBoardHome
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.LocalMainPagerState
import me.weishu.kernelsu.ui.viewmodel.HomeViewModel
import me.weishu.kernelsu.ui.viewmodel.MainPagerConfig
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton

/**
 * 首页分发：默认走磁贴开始屏幕（磁吸磁贴板），用户可一键切回原来的卡片页；
 * 选择记在 [me.weishu.kernelsu.ui.screen.hometiles.HomeLayoutMode] 里，重启保留。
 */
@Composable
fun HomePager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val context = LocalContext.current
    var tilesMode by remember { mutableStateOf(HomeLayoutMode.isTiles(context)) }
    Box(Modifier.fillMaxSize()) {
        if (tilesMode) {
            HomeTileBoardHome(
                navigator = navigator,
                bottomInnerPadding = bottomInnerPadding,
                isCurrentPage = isCurrentPage,
                onSwitchToCards = {
                    tilesMode = false
                    HomeLayoutMode.setTiles(context, false)
                },
            )
        } else {
            HomePagerCards(
                navigator = navigator,
                bottomInnerPadding = bottomInnerPadding,
                isCurrentPage = isCurrentPage,
                onSwitchToTiles = {
                    tilesMode = true
                    HomeLayoutMode.setTiles(context, true)
                },
            )
            // 卡片视图里留一个切换胶囊：磁贴视图自己也有一条编辑铅笔。
            IconButton(
                onClick = {
                    tilesMode = true
                    HomeLayoutMode.setTiles(context, true)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 6.dp, end = 10.dp),
            ) {
                Icon(imageVector = Icons.Rounded.GridView, contentDescription = "磁贴布局")
            }
        }
    }
}

@Composable
private fun HomePagerCards(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
    onSwitchToTiles: () -> Unit = {},
) {
    val viewModel = viewModel<HomeViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val mainPagerState = LocalMainPagerState.current
    val loadingDialog = rememberLoadingDialog()
    val scope = rememberCoroutineScope()
    val latestIsCurrentPage by rememberUpdatedState(isCurrentPage)
    val initialResumeHandled = rememberSaveable { mutableStateOf(false) }

    var hasActivated by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage && !hasActivated) {
            hasActivated = true
            viewModel.refresh()
        }
    }

    LifecycleResumeEffect(Unit) {
        if (initialResumeHandled.value && latestIsCurrentPage) {
            viewModel.refresh()
        }
        initialResumeHandled.value = true
        onPauseOrDispose { }
    }

    val actions = HomeActions(
        onInstallClick = { navigator.push(Route.Install) },
        onOpenUrl = uriHandler::openUri,
        onJailbreakClick = {
            loadingDialog.showLoading()
            context.startService(Intent(context, XecMagicaService::class.java))
            // Manager will be force-stopped and restarted by late-load on success.
            // If that doesn't happen within timeout, jailbreak likely failed.
            scope.launch(Dispatchers.IO) {
                delay(30_000.milliseconds)
                withContext(Dispatchers.Main) {
                    loadingDialog.hide()
                    Toast.makeText(context, R.string.jailbreak_timeout, Toast.LENGTH_LONG).show()
                }
            }
        },
        // 底栏第 0 页 → 第 1 页（超级用户）。用分页状态而不是 push 一条路由：
        // 超级用户不是一个能"返回"的独立页面，它就是底栏那一页。
        onOpenSuperuser = { mainPagerState.animateToPage(1) },
        onOpenSulog = { navigator.push(Route.Sulog) },
        onRefresh = { viewModel.refresh() },
    )

    HomePagerMiuix(
        state = uiState,
        actions = actions,
        bottomInnerPadding = bottomInnerPadding,
    )
}

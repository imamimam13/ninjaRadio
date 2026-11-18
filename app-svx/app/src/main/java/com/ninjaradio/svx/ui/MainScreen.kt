package com.ninjaradio.svx.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ninjaradio.svx.MainViewModel
import com.ninjaradio.svx.ui.components.AddEditServerSheet
import com.ninjaradio.svx.ui.components.AddTalkgroupDialog
import com.ninjaradio.svx.ui.components.BottomNavBar
import com.ninjaradio.svx.ui.components.ChangeTgDialog
import com.ninjaradio.svx.ui.components.LogActivityDialog
import com.ninjaradio.svx.ui.screens.CarModeScreen
import com.ninjaradio.svx.ui.screens.NodesScreen
import com.ninjaradio.svx.ui.screens.PttScreen
import com.ninjaradio.svx.ui.screens.ServersScreen
import com.ninjaradio.svx.ui.screens.SettingsScreen
import com.ninjaradio.svx.ui.screens.SplashScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    var isSplashFinished by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tgSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val logSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (!isSplashFinished) {
        SplashScreen(onTimeout = { isSplashFinished = true })
    } else if (state.isCarMode) {
        CarModeScreen(
            state = state,
            onCloseCarMode = { viewModel.toggleCarMode() },
            onSelectTg = { viewModel.selectTg(it) },
            onPttPressed = { viewModel.onPttPressed() },
            onPttReleased = { viewModel.onPttReleased() },
        )
    } else {
        Scaffold(
            bottomBar = {
                BottomNavBar(
                    selectedTab = state.activeTab,
                    onTabSelected = viewModel::selectTab,
                )
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                when (state.activeTab) {
                    0 -> PttScreen(
                        state = state,
                        onToggleConnection = { viewModel.toggleConnection() },
                        onToggleCarMode = { viewModel.toggleCarMode() },
                        onOpenLogs = { viewModel.openLogsDialog() },
                        onSelectTg = { viewModel.selectTg(it) },
                        onPttPressed = { viewModel.onPttPressed() },
                        onPttReleased = { viewModel.onPttReleased() },
                    )
                    1 -> ServersScreen(
                        state = state,
                        onSelectServer = { viewModel.connectToServer(it) },
                        onToggleConnection = { viewModel.toggleConnection() },
                        onAddServer = { viewModel.openAddServerSheet() },
                        onEditServer = { viewModel.openAddServerSheet(it) },
                        onDeleteServer = { viewModel.deleteServer(it) },
                    )
                    2 -> NodesScreen(
                        state = state,
                        onOpenChangeTg = { viewModel.openChangeTgDialog() },
                    )
                    3 -> SettingsScreen(
                        state = state,
                        onOpenLogs = { viewModel.openLogsDialog() },
                        onUpdateSettings = { viewModel.updateSettings(it) },
                    )
                }
            }
        }
    }

    if (isSplashFinished && state.showAddServerSheet) {
        AddEditServerSheet(
            sheetState = sheetState,
            serverToEdit = state.editingServer,
            onDismiss = { viewModel.closeAddServerSheet() },
            onOpenAddTalkgroup = { viewModel.openAddTalkgroupSheet() },
            onSave = { server -> viewModel.saveServer(server) },
        )
    }

    if (isSplashFinished && state.showAddTalkgroupSheet) {
        AddTalkgroupDialog(
            sheetState = tgSheetState,
            onDismiss = { viewModel.closeAddTalkgroupSheet() },
            onSave = { tgConfig -> viewModel.addTalkgroupToEditingServer(tgConfig) },
        )
    }

    if (isSplashFinished && state.showChangeTgDialog) {
        ChangeTgDialog(
            currentTg = state.currentTg,
            onDismiss = { viewModel.closeChangeTgDialog() },
            onSelectTg = { tg -> viewModel.selectTg(tg) },
        )
    }

    if (isSplashFinished && state.showLogsDialog) {
        LogActivityDialog(
            logs = state.logs,
            sheetState = logSheetState,
            onDismiss = { viewModel.closeLogsDialog() },
        )
    }
}

package com.chamelex.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.chamelex.app.ConnectionViewModel
import com.chamelex.app.R
import com.chamelex.app.ui.components.ConnectionStatusBar
import com.chamelex.app.ui.screens.SettingsScreen
import com.chamelex.app.ui.screens.firmware.FirmwareScreen
import com.chamelex.app.ui.screens.firmware.FirmwareViewModel
import com.chamelex.app.ui.screens.data.DataDetailScreen
import com.chamelex.app.ui.screens.data.DataScreen
import com.chamelex.app.ui.screens.data.DataViewModel
import com.chamelex.app.ui.screens.detection.DetectionScreen
import com.chamelex.app.ui.screens.keys.CardKeysScreen
import com.chamelex.app.ui.screens.keys.KeysGroupScreen
import com.chamelex.app.ui.screens.keys.KeysScreen
import com.chamelex.app.ui.screens.keys.KeysViewModel
import com.chamelex.app.ui.screens.reader.ReaderScreen
import com.chamelex.app.ui.screens.slots.EmulatorConfigScreen
import com.chamelex.app.ui.screens.slots.SlotDetailScreen
import com.chamelex.app.ui.screens.slots.SlotViewModel
import com.chamelex.app.ui.screens.slots.SlotsScreen
import com.chamelex.connection.ConnectionEvent

private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab("reader_graph", R.string.tab_reader, Icons.Filled.Nfc),
    Tab("slots_graph", R.string.tab_slots, Icons.Filled.ViewModule),
    Tab("data_graph", R.string.tab_data, Icons.Filled.Folder),
    Tab("keys_graph", R.string.tab_keys, Icons.Filled.VpnKey),
    Tab("settings", R.string.tab_settings, Icons.Filled.Settings),
)

/** 嵌套图共享的 SlotViewModel（作用域为 slots_graph）。 */
@Composable
private fun slotGraphViewModel(
    navController: NavHostController,
    entry: NavBackStackEntry,
): SlotViewModel {
    val graphEntry = remember(entry) { navController.getBackStackEntry("slots_graph") }
    return hiltViewModel(graphEntry)
}

/** 嵌套图共享的 DataViewModel（作用域为 data_graph）。 */
@Composable
private fun dataGraphViewModel(
    navController: NavHostController,
    entry: NavBackStackEntry,
): DataViewModel {
    val graphEntry = remember(entry) { navController.getBackStackEntry("data_graph") }
    return hiltViewModel(graphEntry)
}

/** 嵌套图共享的 KeysViewModel（作用域为 keys_graph）。 */
@Composable
private fun keysGraphViewModel(
    navController: NavHostController,
    entry: NavBackStackEntry,
): KeysViewModel {
    val graphEntry = remember(entry) { navController.getBackStackEntry("keys_graph") }
    return hiltViewModel(graphEntry)
}

@Composable
fun AppRoot(viewModel: ConnectionViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    // 断线/连接失败等全局事件 -> Snackbar
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is ConnectionEvent.Lost -> event.message
                is ConnectionEvent.Failure -> event.message
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = { ConnectionStatusBar(viewModel) },
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "reader_graph",
            modifier = Modifier.padding(padding),
        ) {
            navigation(startDestination = "reader", route = "reader_graph") {
                composable("reader") {
                    ReaderScreen(
                        viewModel = hiltViewModel(),
                        snackbarHostState = snackbarHostState,
                        onOpenDetection = { navController.navigate("detection") },
                    )
                }
                composable("detection") {
                    DetectionScreen(
                        viewModel = hiltViewModel(),
                        snackbarHostState = snackbarHostState,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            navigation(startDestination = "slots", route = "slots_graph") {
                composable("slots") { entry ->
                    val vm = slotGraphViewModel(navController, entry)
                    SlotsScreen(
                        viewModel = vm,
                        snackbarHostState = snackbarHostState,
                        onOpenDetail = { navController.navigate("slot_detail/${it.uiIndex}") },
                    )
                }
                composable(
                    "slot_detail/{slot}",
                    arguments = listOf(navArgument("slot") { type = NavType.IntType }),
                ) { entry ->
                    val vm = slotGraphViewModel(navController, entry)
                    SlotDetailScreen(
                        slotIndex = entry.arguments?.getInt("slot") ?: 1,
                        viewModel = vm,
                        snackbarHostState = snackbarHostState,
                        onBack = { navController.popBackStack() },
                        onOpenEmuConfig = { navController.navigate("slot_emu/${it.uiIndex}") },
                    )
                }
                composable(
                    "slot_emu/{slot}",
                    arguments = listOf(navArgument("slot") { type = NavType.IntType }),
                ) { entry ->
                    val vm = slotGraphViewModel(navController, entry)
                    EmulatorConfigScreen(
                        slotIndex = entry.arguments?.getInt("slot") ?: 1,
                        viewModel = vm,
                        snackbarHostState = snackbarHostState,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            navigation(startDestination = "data", route = "data_graph") {
                composable("data") { entry ->
                    val vm = dataGraphViewModel(navController, entry)
                    DataScreen(
                        viewModel = vm,
                        snackbarHostState = snackbarHostState,
                        onOpenDetail = { navController.navigate("data_detail/$it") },
                    )
                }
                composable(
                    "data_detail/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry ->
                    val vm = dataGraphViewModel(navController, entry)
                    DataDetailScreen(
                        dumpId = entry.arguments?.getLong("id") ?: 0L,
                        viewModel = vm,
                        snackbarHostState = snackbarHostState,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            navigation(startDestination = "keys", route = "keys_graph") {
                composable("keys") { entry ->
                    val vm = keysGraphViewModel(navController, entry)
                    KeysScreen(
                        viewModel = vm,
                        snackbarHostState = snackbarHostState,
                        onOpenGroup = { navController.navigate("keys_group/$it") },
                        onOpenCards = { navController.navigate("keys_cards") },
                    )
                }
                composable(
                    "keys_group/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry ->
                    val vm = keysGraphViewModel(navController, entry)
                    KeysGroupScreen(
                        groupId = entry.arguments?.getLong("id") ?: 0L,
                        viewModel = vm,
                        snackbarHostState = snackbarHostState,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("keys_cards") { entry ->
                    val vm = keysGraphViewModel(navController, entry)
                    CardKeysScreen(
                        viewModel = vm,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable("settings") {
                SettingsScreen(
                    viewModel = viewModel,
                    onOpenFirmware = { navController.navigate("firmware") },
                    onOpenDfuDevice = { address, name ->
                        navController.navigate("firmware?dfuAddress=$address&dfuName=$name")
                    },
                    onOpenAbout = { navController.navigate("about") },
                )
            }
            composable("about") {
                com.chamelex.app.ui.screens.settings.AboutScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                "firmware?dfuAddress={dfuAddress}&dfuName={dfuName}",
                arguments = listOf(
                    navArgument("dfuAddress") { nullable = true; defaultValue = null },
                    navArgument("dfuName") { nullable = true; defaultValue = null },
                ),
            ) { entry ->
                FirmwareScreen(
                    viewModel = hiltViewModel(),
                    dfuAddress = entry.arguments?.getString("dfuAddress"),
                    dfuName = entry.arguments?.getString("dfuName"),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

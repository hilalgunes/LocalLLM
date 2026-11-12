package com.hilal.llm.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

enum class MainDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    EXPERIMENT("experiment", "Model deneyi", Icons.Default.Science),
    LAB("lab", "Laboratuvar", Icons.Default.Science),
    CHAT("chat", "Sohbet", Icons.AutoMirrored.Filled.Chat),
    RUN("run", "Run", Icons.Default.PlayArrow),
    COMPARE("compare", "Compare", Icons.AutoMirrored.Filled.CompareArrows),
    EMBEDDING("embedding", "Embedding", Icons.Default.Hub),
    RAG("rag", "RAG", Icons.Default.AccountTree),
    TEST("test", "Test", Icons.Default.Science),
    HISTORY("history", "Sonuçlar", Icons.Default.History)
}

@Composable
fun MainBottomNavigation(
    navController: NavHostController
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        listOf(MainDestination.CHAT, MainDestination.RAG, MainDestination.LAB, MainDestination.HISTORY).forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route || (destination == MainDestination.LAB && currentRoute in setOf(MainDestination.EXPERIMENT.route, MainDestination.RUN.route, MainDestination.COMPARE.route, MainDestination.EMBEDDING.route, MainDestination.TEST.route)),
                onClick = {
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = destination != MainDestination.LAB
                        }
                        launchSingleTop = true
                        restoreState = destination != MainDestination.LAB
                    }
                },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.label
                    )
                },
                label = { Text(destination.label) }
            )
        }
    }
}

package dev.aster.ide.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/** One panel catalog feeds both styles, their menus and the retained panel host. */
internal data class WorkbenchPanel(val id: String, val title: String, val icon: ImageVector, val menuTitle: String = title)
internal val WorkbenchPanels = listOf(
    WorkbenchPanel("terminal", "终端", Icons.Outlined.Terminal),
    WorkbenchPanel("problems", "问题", Icons.Outlined.ErrorOutline),
    WorkbenchPanel("build", "构建", Icons.Outlined.Build, "构建输出"),
    WorkbenchPanel("artifacts", "产物", Icons.Outlined.Inventory2, "编译产物"),
    WorkbenchPanel("symbols", "符号", Icons.Outlined.DataObject, "文档符号"),
    WorkbenchPanel("logs", "日志", Icons.Outlined.Description)
)

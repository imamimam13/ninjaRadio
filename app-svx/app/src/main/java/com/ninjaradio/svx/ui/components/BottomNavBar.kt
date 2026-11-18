package com.ninjaradio.svx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninjaradio.svx.ui.theme.NinjaNavActive
import com.ninjaradio.svx.ui.theme.NinjaNavBg
import com.ninjaradio.svx.ui.theme.NinjaTextMuted

sealed class NavTab(val index: Int, val title: String, val icon: ImageVector) {
    object Ptt : NavTab(0, "PTT", Icons.Default.Radio)
    object Servers : NavTab(1, "Servers", Icons.Default.Dns)
    object Nodes : NavTab(2, "Nodes", Icons.Default.Group)
    object Settings : NavTab(3, "Settings", Icons.Default.Settings)
}

val tabs = listOf(NavTab.Ptt, NavTab.Servers, NavTab.Nodes, NavTab.Settings)

@Composable
fun BottomNavBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(NinjaNavBg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { tab ->
            val isSelected = tab.index == selectedTab
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onTabSelected(tab.index) }
                    .padding(vertical = 4.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) NinjaNavActive else Color.Transparent)
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.title,
                        tint = if (isSelected) Color.White else NinjaTextMuted,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    text = tab.title,
                    fontSize = 11.sp,
                    color = if (isSelected) Color.White else NinjaTextMuted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

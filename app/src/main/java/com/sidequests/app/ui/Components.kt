package com.sidequests.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.statusBarsPadding
import com.sidequests.app.context.DeviceStatus
import com.sidequests.app.R
import com.sidequests.app.model.AppScreen
import com.sidequests.app.model.LocationMode
import com.sidequests.app.model.Quest
import com.sidequests.app.ui.theme.DiscoveryTeal
import com.sidequests.app.ui.theme.ExplorerIndigo
import com.sidequests.app.ui.theme.QuestAmber

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = ExplorerIndigo,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = if (containerColor.luminance() > .35f) Color(0xFF1A1830) else Color.White,
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedColor: Color = ExplorerIndigo,
    emoji: String? = null,
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(999.dp)).clickable(role = Role.Button, onClick = onClick),
        color = if (selected) selectedColor else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(999.dp),
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (emoji != null) Text(emoji)
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    selected && selectedColor.luminance() > .35f -> Color(0xFF1A1830)
                    selected -> Color.White
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
fun MetaPill(text: String, accent: Color = MaterialTheme.colorScheme.surfaceVariant) {
    Surface(color = accent, shape = RoundedCornerShape(999.dp)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestCard(
    quest: Quest,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onSkip: (() -> Unit)? = null,
    /** Shown when this quest has an open attempt, e.g. "In progress · 2/4". */
    progressLabel: String? = null,
    onStartNow: (() -> Unit)? = null,
    startNowEnabled: Boolean = true,
    completionLabel: String? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(quest.emoji, fontSize = 29.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (quest.isNew) MetaPill("NEW", QuestAmber.copy(alpha = .2f))
                        if (quest.isSponsored) MetaPill("SPONSORED", QuestAmber.copy(alpha = .22f))
                        if (quest.isGroup) MetaPill("GROUP", ExplorerIndigo.copy(alpha = .16f))
                        if (progressLabel != null) MetaPill("⚡ $progressLabel", DiscoveryTeal.copy(alpha = .22f))
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        quest.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(quest.category, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Text(
                quest.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (completionLabel != null) {
                MetaPill(completionLabel, DiscoveryTeal.copy(alpha = .16f))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MetaPill("⏱ ${quest.duration}")
                MetaPill("💰 ${quest.budget}")
                MetaPill(if (quest.location == LocationMode.Gps) "📍 ${quest.distance}" else "🏠 Anywhere")
            }
            if (onSkip != null || onStartNow != null) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (onSkip != null) {
                        TextButton(onClick = onSkip) { Text("Not for me") }
                    }
                    if (onStartNow != null) {
                        Button(onClick = onStartNow, enabled = startNowEnabled) {
                            Text(if (progressLabel == null) "Start now" else "Continue")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BottomBar(
    screen: AppScreen,
    onNavigate: (AppScreen) -> Unit,
) {
    val active = when (screen) {
        AppScreen.Explorer, AppScreen.VarietyDiscovery -> AppScreen.Explorer
        AppScreen.Nearby -> AppScreen.Nearby
        AppScreen.Social -> AppScreen.Social
        AppScreen.ActiveQuest, AppScreen.GroupQuest, AppScreen.QuestHome -> AppScreen.QuestHome
        AppScreen.Profile, AppScreen.OfflinePack -> AppScreen.Profile
        else -> null
    }
    val tabs = listOf(
        Triple(AppScreen.Explorer, R.drawable.ic_explore, "Explore"),
        Triple(AppScreen.Nearby, R.drawable.ic_nearby, "Nearby"),
        Triple(AppScreen.Social, R.drawable.ic_social, "Social"),
        Triple(AppScreen.QuestHome, R.drawable.ic_quest, "Quest"),
        Triple(AppScreen.Profile, R.drawable.ic_profile, "Profile"),
    )

    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        tabs.forEach { (target, icon, label) ->
            NavigationBarItem(
                selected = active == target,
                onClick = { onNavigate(target) },
                icon = { Icon(painterResource(icon), contentDescription = null) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
fun ContextNotificationBanner(
    quest: Quest,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = ExplorerIndigo),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(quest.emoji, fontSize = 28.sp)
            Column(modifier = Modifier.weight(1f).clickable(onClick = onOpen)) {
                Text("RIGHT NOW NEAR YOU", color = QuestAmber, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Text(quest.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("${quest.distance} • ${quest.budget}", color = Color.White.copy(alpha = .78f), fontSize = 12.sp)
            }
            Text(
                "✕",
                modifier = Modifier.clip(CircleShape).clickable(onClick = onDismiss).padding(8.dp),
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun AccentStat(label: String, value: String, emoji: String, color: Color = DiscoveryTeal) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = .13f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 21.sp)
            Text(value, fontWeight = FontWeight.ExtraBold, color = color)
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
        }
    }
}

/**
 * Context-aware feature (Sprint 2, Carlos): banner that reacts to the phone context.
 * Offline has priority over low battery. Recovery is shown briefly after reconnection.
 */
@Composable
fun DeviceStatusBanner(
    status: DeviceStatus,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    connectionRestored: Boolean = false,
    offlineBody: String? = null,
) {
    val title: String
    val body: String
    val color: Color
    when {
        !status.isOnline -> {
            title = "You're offline"
            body = offlineBody
                ?: "Showing the last saved data. Quest progress is saved on this device and syncs when you reconnect."
            color = MaterialTheme.colorScheme.error
        }
        connectionRestored -> {
            title = "You're back online"
            body = "Internet connection restored. Saved data can refresh automatically."
            color = DiscoveryTeal
        }
        status.isLowBattery -> {
            title = "Battery saver"
            body = "Battery at ${status.batteryPercent}%. Location and weather are refreshed less often."
            color = QuestAmber
        }
        else -> return
    }

    val contentColor = if (!status.isOnline) MaterialTheme.colorScheme.onError else Color(0xFF1A1830)
    Surface(
        modifier = modifier.fillMaxWidth().statusBarsPadding(),
        color = color,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
            if (!status.isOnline && onRetry != null) {
                Text(
                    "Retry",
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onRetry)
                        .background(contentColor.copy(alpha = .12f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

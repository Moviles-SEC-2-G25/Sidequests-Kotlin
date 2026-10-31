package com.sidequests.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.statusBarsPadding
import com.sidequests.app.context.DeviceStatus
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
        modifier = modifier.fillMaxWidth().height(56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium, color = Color.White)
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
        modifier = modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onClick),
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
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
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

@Composable
fun QuestCard(
    quest: Quest,
    onOpen: () -> Unit,
    onSkip: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(quest.emoji, fontSize = 29.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (quest.isNew) MetaPill("NEW", QuestAmber.copy(alpha = .2f))
                        if (quest.isSponsored) MetaPill("SPONSORED", QuestAmber.copy(alpha = .22f))
                        if (quest.isGroup) MetaPill("GROUP", ExplorerIndigo.copy(alpha = .16f))
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
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                MetaPill("⏱ ${quest.duration}")
                MetaPill("💰 ${quest.budget}")
                MetaPill(if (quest.location == LocationMode.Gps) "📍 ${quest.distance}" else "🏠 Anywhere")
            }
            if (onSkip != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        text = "Not for me",
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onSkip).padding(6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f),
                    )
                }
            }
        }
    }
}

@Composable
fun BottomBar(
    screen: AppScreen,
    questAvailable: Boolean,
    onNavigate: (AppScreen) -> Unit,
) {
    val active = when (screen) {
        AppScreen.Explorer, AppScreen.QuestDetail -> AppScreen.Explorer
        AppScreen.ActiveQuest, AppScreen.GroupQuest -> AppScreen.ActiveQuest
        AppScreen.Profile -> AppScreen.Profile
        else -> null
    }
    val tabs = listOf(
        Triple(AppScreen.Explorer, "🧭", "Explore"),
        Triple(AppScreen.ActiveQuest, "⚡", "Quest"),
        Triple(AppScreen.Profile, "👤", "Profile"),
    )

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            tabs.forEach { (target, emoji, label) ->
                val selected = active == target
                val enabled = target != AppScreen.ActiveQuest || questAvailable
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = enabled) { onNavigate(target) }
                        .padding(horizontal = 24.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(emoji, fontSize = 20.sp, color = Color.White.copy(alpha = if (enabled) 1f else .35f))
                    Text(
                        label.uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .28f)
                            selected -> ExplorerIndigo
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = .5f)
                        },
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier.size(4.dp).clip(CircleShape).background(if (selected) ExplorerIndigo else Color.Transparent)
                    )
                }
            }
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
 * Offline has priority over low battery. Nothing is shown when everything is normal.
 */
@Composable
fun DeviceStatusBanner(
    status: DeviceStatus,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title: String
    val body: String
    val color: Color
    when {
        !status.isOnline -> {
            title = "You're offline"
            body = "Showing the last saved data. Actions that need internet will work again when you reconnect."
            color = MaterialTheme.colorScheme.error
        }
        status.isLowBattery -> {
            title = "Battery saver"
            body = "Battery at ${status.batteryPercent}%. Location and weather are refreshed less often."
            color = QuestAmber
        }
        else -> return
    }

    Surface(
        modifier = modifier.fillMaxWidth().statusBarsPadding(),
        color = color,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(body, color = Color.White.copy(alpha = .9f), fontSize = 11.sp)
            }
            if (!status.isOnline) {
                Text(
                    "Retry",
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onRetry)
                        .background(Color.White.copy(alpha = .2f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

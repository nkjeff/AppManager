package com.nkjeff.appmanager

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process as AndroidProcess
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.util.Locale
import android.icu.text.Transliterator

private const val SHIZUKU_REQUEST = 4102
private val BLACK = Color.Black
private val WHITE = Color.White

data class AppEntry(
    val packageName: String,
    val label: String,
    val userId: Int,
    val launchCount: Int,
    val running: Boolean,
    val enabled: Boolean,
    val icon: Bitmap?
) {
    val key: String get() = "$userId:$packageName"
}

class MainActivity : ComponentActivity() {
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST && grantResult == PackageManager.PERMISSION_GRANTED) {
            ShizukuShell.bind(this) { runOnUiThread { recreate() } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, true)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            ShizukuShell.bind(this) { runOnUiThread { recreate() } }
        }
        setContent { AbbScreen() }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AbbScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val leftState = rememberLazyListState()
    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    var whitelist by remember { mutableStateOf(loadSet(context, "whitelist")) }
    var selectedInitial by remember { mutableStateOf("") }
    var packagePreview by remember { mutableStateOf("") }
    var usageAllowed by remember { mutableStateOf(hasUsageAccess(context)) }
    var shizukuGranted by remember {
        mutableStateOf(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
    }
    var refreshToken by remember { mutableStateOf(0) }

    fun refresh() {
        usageAllowed = hasUsageAccess(context)
        shizukuGranted = Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        apps = loadApps(context, shizukuGranted, usageAllowed)
        refreshToken++
    }

    fun toggleWhitelist(entry: AppEntry) {
        whitelist = whitelist.toMutableSet().also {
            if (!it.add(entry.key)) it.remove(entry.key)
        }
        saveSet(context, "whitelist", whitelist)
    }

    fun freeze(entry: AppEntry) {
        if (!ensureShizuku()) return
        runShell("pm disable-user --user ${entry.userId} ${entry.packageName}")
        refresh()
    }

    fun unfreezeAndLaunch(entry: AppEntry) {
        if (ensureShizuku()) {
            runShell("pm enable --user ${entry.userId} ${entry.packageName}")
            val intent = context.packageManager.getLaunchIntentForPackage(entry.packageName)
            if (intent != null) {
                val component = intent.component?.flattenToString()
                if (component != null) {
                    runShell("am start --user ${entry.userId} -n $component")
                } else if (entry.userId == 0) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
        } else if (entry.userId == 0) {
            val intent = context.packageManager.getLaunchIntentForPackage(entry.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null) context.startActivity(intent)
        }
        refresh()
    }

    LaunchedEffect(Unit) {
        if (!usageAllowed) {
            try {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { }
        }
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_REQUEST)
        }
        refresh()
    }

    val leftApps = apps
        .filter { selectedInitial.isBlank() || pinyinInitial(it.label) == selectedInitial }
        .sortedWith(compareByDescending<AppEntry> { it.launchCount }.thenBy { it.label.lowercase(Locale.ROOT) })
    val rightApps = apps.filter { it.running }.sortedBy { it.label.lowercase(Locale.ROOT) }

    Surface(modifier = Modifier.fillMaxSize(), color = BLACK) {
        Column(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .background(BLACK)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(0.085f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = packagePreview,
                    color = WHITE,
                    fontSize = 8.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }

            key(refreshToken) {
                Box(modifier = Modifier.fillMaxWidth().weight(0.83f)) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        AppListColumn(
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            entries = leftApps,
                            listState = leftState,
                            whitelist = whitelist,
                            onTouch = { packagePreview = it.packageName },
                            onClick = { unfreezeAndLaunch(it) },
                            onLongClick = { toggleWhitelist(it) }
                        )
                        AppListColumn(
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            entries = rightApps,
                            listState = rememberLazyListState(),
                            whitelist = whitelist,
                            onTouch = { packagePreview = it.packageName },
                            onClick = { unfreezeAndLaunch(it) },
                            onLongClick = { freeze(it) }
                        )
                    }
                    AlphabetRail(
                        modifier = Modifier.align(Alignment.Center).zIndex(2f),
                        selected = selectedInitial,
                        onSelect = { selectedInitial = it },
                        onReset = {
                            selectedInitial = ""
                            scope.launch { leftState.animateScrollToItem(0) }
                        }
                    )
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth().weight(0.085f),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(0.94f)
                        .height(42.dp)
                        .border(1.dp, WHITE, RoundedCornerShape(2.dp))
                        .combinedClickable(
                            onClick = {
                                if (!ensureShizuku()) return@combinedClickable
                                rightApps.filter {
                                    it.key !in whitelist && it.packageName != context.packageName
                                }.forEach { entry ->
                                    runShell("pm disable-user --user ${entry.userId} ${entry.packageName}")
                                }
                                refresh()
                            },
                            onLongClick = { }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("停用", color = WHITE, fontSize = 13.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppListColumn(
    modifier: Modifier,
    entries: List<AppEntry>,
    listState: LazyListState,
    whitelist: Set<String>,
    onTouch: (AppEntry) -> Unit,
    onClick: (AppEntry) -> Unit,
    onLongClick: (AppEntry) -> Unit
) {
    LazyColumn(
        modifier = modifier,
        state = listState,
        horizontalAlignment = Alignment.Start
    ) {
        items(entries, key = { it.key }) { entry ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 3.dp, vertical = 5.dp)
                    .combinedClickable(
                        onClick = { onClick(entry) },
                        onLongClick = { onLongClick(entry) }
                    )
                    .pointerInput(entry.key) {
                        awaitEachGesture {
                            awaitFirstDown()
                            onTouch(entry)
                        }
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                if (entry.key in whitelist) {
                    Text(
                        text = "☆",
                        color = WHITE,
                        fontSize = 12.sp,
                        modifier = Modifier.width(15.dp),
                        textAlign = TextAlign.Center
                    )
                } else {
                    Spacer(modifier = Modifier.width(15.dp))
                }
                if (entry.icon != null) {
                    Image(
                        bitmap = entry.icon.asImageBitmap(),
                        contentDescription = entry.label,
                        modifier = Modifier.size(29.dp).alpha(if (entry.enabled) 1f else 0.5f)
                    )
                } else {
                    Spacer(modifier = Modifier.size(29.dp))
                }
                Text(
                    text = entry.label,
                    color = WHITE,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    maxLines = 2,
                    modifier = Modifier.weight(1f).padding(start = 5.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlphabetRail(
    modifier: Modifier,
    selected: String,
    onSelect: (String) -> Unit,
    onReset: () -> Unit
) {
    val letters = ('A'..'Z').map { it.toString() } + "#"
    LazyColumn(
        modifier = modifier.width(16.dp)
            .background(BLACK)
            .padding(vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(letters) { letter ->
            Text(
                text = letter,
                modifier = Modifier.fillMaxWidth()
                    .combinedClickable(
                        onClick = { onSelect(letter) },
                        onLongClick = { onReset() }
                    )
                    .padding(vertical = 2.dp),
                color = if (selected == letter) WHITE else Color(0xFFBDBDBD),
                fontSize = 10.sp,
                fontWeight = if (selected == letter) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun loadApps(context: Context, canShell: Boolean, usageAllowed: Boolean): List<AppEntry> {
    val pm = context.packageManager
    val users = if (canShell) listUsers() else listOf(0)
    val running = if (canShell) runningPackages() else emptySet()
    val counts = if (usageAllowed) launchCounts(context) else emptyMap()
    val ownPackage = context.packageName
    val installedForCurrentUser = try {
        pm.getInstalledApplications(PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS)
            .filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                    it.packageName != ownPackage
            }
    } catch (_: Exception) {
        emptyList()
    }

    val result = mutableListOf<AppEntry>()
    users.take(7).forEach { userId ->
        val packagesForUser = if (canShell) listPackagesForUser(userId) else null
        val disabledForUser = if (canShell) disabledPackagesForUser(userId) else emptySet()
        installedForCurrentUser.forEach { info ->
            if (packagesForUser != null && info.packageName !in packagesForUser) return@forEach
            val label = try {
                pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                info.packageName
            }
            val icon = try {
                drawableBitmap(info.loadIcon(pm))
            } catch (_: Exception) {
                null
            }
            result += AppEntry(
                packageName = info.packageName,
                label = label,
                userId = userId,
                launchCount = counts[info.packageName] ?: 0,
                running = "$userId:${info.packageName}" in running,
                enabled = info.packageName !in disabledForUser,
                icon = icon
            )
        }
    }
    return result.distinctBy { it.key }
}

private fun launchCounts(context: Context): Map<String, Int> {
    val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val end = System.currentTimeMillis()
    val events = manager.queryEvents(end - 30L * 24 * 60 * 60 * 1000, end)
    val event = UsageEvents.Event()
    val counts = mutableMapOf<String, Int>()
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
        ) {
            counts[event.packageName] = (counts[event.packageName] ?: 0) + 1
        }
    }
    return counts
}

private fun hasUsageAccess(context: Context): Boolean {
    val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return try {
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            AndroidProcess.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }
}

private fun listUsers(): List<Int> {
    val output = runShell("pm list users")
    val ids = Regex("UserInfo\\{(\\d+):").findAll(output)
        .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
        .toList()
    return (listOf(0) + ids).distinct()
}

private fun listPackagesForUser(userId: Int): Set<String> {
    return Regex("package:([^\\s]+)")
        .findAll(runShell("pm list packages --user $userId"))
        .map { it.groupValues[1] }
        .toSet()
}

private fun disabledPackagesForUser(userId: Int): Set<String> {
    return Regex("package:([^\\s]+)")
        .findAll(runShell("pm list packages -d --user $userId"))
        .map { it.groupValues[1] }
        .toSet()
}

private fun runningPackages(): Set<String> {
    val output = runShell("ps -A -o USER,NAME")
    return output.lineSequence().drop(1).mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2) return@mapNotNull null
        val userId = Regex("^u(\\d+)_").find(parts[0])?.groupValues?.get(1)?.toIntOrNull()
            ?: parts[0].toIntOrNull()?.div(100000)
            ?: 0
        val processName = parts.last().substringBefore(':')
        if (processName.contains('.') && !processName.startsWith("android.")) {
            "$userId:$processName"
        } else {
            null
        }
    }.toSet()
}

private fun ensureShizuku(): Boolean {
    if (!Shizuku.pingBinder()) return false
    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
        Shizuku.requestPermission(SHIZUKU_REQUEST)
        return false
    }
    return true
}

private fun runShell(command: String): String = ShizukuShell.runCommand(command)

private fun pinyinInitial(label: String): String {
    if (label.isBlank()) return "#"
    val first = label.trim().first()
    if (first.isDigit()) {
        val pinyin = listOf("LING", "YI", "ER", "SAN", "SI", "WU", "LIU", "QI", "BA", "JIU")[first.digitToInt()]
        return pinyin.first().toString()
    }
    return try {
        val transliterator = Transliterator.getInstance("Han-Latin; Latin-ASCII")
        val converted = transliterator.transliterate(first.toString()).uppercase(Locale.ROOT)
        converted.firstOrNull()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"
    } catch (_: Exception) {
        first.uppercaseChar().takeIf { it in 'A'..'Z' }?.toString() ?: "#"
    }
}

private fun drawableBitmap(drawable: Drawable): Bitmap {
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

private fun loadSet(context: Context, key: String): Set<String> =
    context.getSharedPreferences("abb", Context.MODE_PRIVATE)
        .getStringSet(key, emptySet())?.toSet() ?: emptySet()

private fun saveSet(context: Context, key: String, value: Set<String>) {
    context.getSharedPreferences("abb", Context.MODE_PRIVATE)
        .edit().putStringSet(key, value.toSet()).apply()
}

package com.nkjeff.appmanager

import android.app.Activity
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
import android.text.TextUtils
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.core.view.WindowCompat
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import android.icu.text.Transliterator

private const val SHIZUKU_REQUEST = 4102
private val OLED_BLACK = Color.Black
private val SOFT_BLUE = Color(0xFF83B9F5)
private val DIM_WHITE = Color(0xFFEDEDED)

data class AppEntry(
    val packageName: String,
    val label: String,
    val userId: Int,
    val slot: Int,
    val launchCount: Int,
    val running: Boolean,
    val icon: Bitmap?
) {
    val key: String get() = "$userId:$packageName"
}

class MainActivity : ComponentActivity() {
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, true)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContent { AppManagerScreen() }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppManagerScreen() {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    var whitelist by remember { mutableStateOf(loadSet(context, "whitelist")) }
    var pinned by remember { mutableStateOf(loadSet(context, "pinned")) }
    var selectedInitial by remember { mutableStateOf("全部") }
    var touchedPackage by remember { mutableStateOf("") }
    var hasUsage by remember { mutableStateOf(hasUsageAccess(context)) }
    var shizukuGranted by remember { mutableStateOf(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) }

    fun refresh() {
        hasUsage = hasUsageAccess(context)
        shizukuGranted = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        apps = loadApps(context, shizukuGranted, hasUsage)
        selectedInitial = "全部"
    }

    LaunchedEffect(Unit) {
        if (!hasUsage) {
            try { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_REQUEST)
        }
        refresh()
    }

    val leftApps = apps.sortedWith(compareByDescending<AppEntry> { it.launchCount }.thenBy { it.label.lowercase(Locale.ROOT) })
    val centerApps = apps.filter { it.running }
    val rightApps = apps.filter { it.key in pinned }
        .filter { selectedInitial == "全部" || pinyinInitial(it.label) == selectedInitial }
        .sortedBy { it.label.lowercase(Locale.ROOT) }

    Surface(modifier = Modifier.fillMaxSize(), color = OLED_BLACK) {
        Column(modifier = Modifier.fillMaxSize().background(OLED_BLACK)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { refresh() },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF171717), contentColor = DIM_WHITE)
                ) { Text("刷新", fontSize = 14.sp) }
                Button(
                    onClick = {
                        if (!Shizuku.pingBinder()) return@Button
                        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                            Shizuku.requestPermission(SHIZUKU_REQUEST)
                            return@Button
                        }
                        val targets = centerApps.filter { it.key !in whitelist && it.packageName != context.packageName }
                        targets.forEach { entry ->
                            runShell("pm disable-user --user ${entry.userId} ${entry.packageName}")
                        }
                        refresh()
                    },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF202A35), contentColor = SOFT_BLUE)
                ) { Text("一键停用", fontSize = 14.sp) }
            }

            Row(modifier = Modifier.fillMaxSize()) {
                AppColumn(
                    modifier = Modifier.weight(1f),
                    entries = leftApps,
                    whitelist = whitelist,
                    pinned = pinned,
                    onTouch = { touchedPackage = it.packageName },
                    onLaunch = { launchApp(context, it) },
                    onLongPress = {}
                )
                AppColumn(
                    modifier = Modifier.weight(1f),
                    entries = centerApps,
                    whitelist = whitelist,
                    pinned = pinned,
                    onTouch = { touchedPackage = it.packageName },
                    onLaunch = { launchApp(context, it) },
                    onLongPress = { entry ->
                        whitelist = whitelist.toMutableSet().also {
                            if (!it.add(entry.key)) it.remove(entry.key)
                        }
                        saveSet(context, "whitelist", whitelist)
                    }
                )
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    AppColumn(
                        modifier = Modifier.fillMaxSize().padding(start = 17.dp),
                        entries = rightApps,
                        whitelist = whitelist,
                        pinned = pinned,
                        onTouch = { touchedPackage = it.packageName },
                        onLaunch = { launchApp(context, it) },
                        onLongPress = { entry ->
                            pinned = pinned.toMutableSet().also {
                                if (!it.add(entry.key)) it.remove(entry.key)
                            }
                            saveSet(context, "pinned", pinned)
                        }
                    )
                    AlphabetRail(
                        modifier = Modifier.align(Alignment.CenterStart),
                        selected = selectedInitial,
                        onSelect = { selectedInitial = it }
                    )
                }
            }
            Text(
                text = touchedPackage,
                modifier = Modifier.fillMaxWidth().padding(bottom = 1.dp, top = 2.dp),
                color = Color.White,
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppColumn(
    modifier: Modifier,
    entries: List<AppEntry>,
    whitelist: Set<String>,
    pinned: Set<String>,
    onTouch: (AppEntry) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onLongPress: (AppEntry) -> Unit
) {
    LazyColumn(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        items(entries, key = { it.key }) { entry ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1.dp, vertical = 5.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (entry.key in whitelist || entry.key in pinned) Color(0xFF101820) else OLED_BLACK)
                    .pointerInput(entry.key) {
                        awaitEachGesture {
                            awaitFirstDown()
                            onTouch(entry)
                        }
                    }
                    .combinedClickable(
                        onClick = { onLaunch(entry) },
                        onLongClick = { onLongPress(entry) }
                    )
                    .padding(vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (entry.icon != null) {
                    Image(
                        bitmap = entry.icon.asImageBitmap(),
                        contentDescription = entry.label,
                        modifier = Modifier.size(52.dp)
                    )
                } else {
                    Spacer(Modifier.size(52.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(
                        text = entry.label,
                        color = DIM_WHITE,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                    if (entry.slot in 0..5) {
                        Text(" ${entry.slot}", color = SOFT_BLUE, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlphabetRail(modifier: Modifier, selected: String, onSelect: (String) -> Unit) {
    val letters = listOf("全部") + ('A'..'Z').map { it.toString() } + "#"
    LazyColumn(
        modifier = modifier.width(17.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF090D12)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(letters) { letter ->
            Text(
                text = if (letter == "全部") "全" else letter,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .combinedClickable(onClick = { onSelect(letter) }, onLongClick = {}),
                color = if (selected == letter) SOFT_BLUE else Color(0xFFB8B8B8),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                fontWeight = if (selected == letter) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

private fun loadApps(context: Context, canShell: Boolean, usageAllowed: Boolean): List<AppEntry> {
    val pm = context.packageManager
    val users = if (canShell) listUsers() else listOf(0)
    val running = if (canShell) runningPackages() else emptySet()
    val counts = if (usageAllowed) launchCounts(context) else emptyMap()
    val own = context.packageName
    val result = mutableListOf<AppEntry>()

    users.take(6).forEachIndexed { slot, userId ->
        val packages = if (canShell && userId != 0) listPackagesForUser(userId) else null
        val installed = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != own }
        } catch (_: Exception) { emptyList() }

        installed.forEach { info ->
            if (packages != null && info.packageName !in packages) return@forEach
            val label = try { pm.getApplicationLabel(info).toString() } catch (_: Exception) { info.packageName }
            val icon = try { drawableBitmap(info.loadIcon(pm)) } catch (_: Exception) { null }
            result += AppEntry(
                packageName = info.packageName,
                label = label,
                userId = userId,
                slot = slot,
                launchCount = counts[info.packageName] ?: 0,
                running = running.any { it == info.packageName || it.startsWith(info.packageName + ":") },
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
        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED || event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
            counts[event.packageName] = (counts[event.packageName] ?: 0) + 1
        }
    }
    return counts
}

private fun hasUsageAccess(context: Context): Boolean {
    val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return try {
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, AndroidProcess.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }
}

private fun listUsers(): List<Int> {
    val output = runShell("pm list users")
    val ids = Regex("UserInfo\\{(\\d+):").findAll(output).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.toList()
    return (listOf(0) + ids).distinct().take(6)
}

private fun listPackagesForUser(userId: Int): Set<String> {
    return Regex("package:([^\\s]+)").findAll(runShell("pm list packages --user $userId"))
        .map { it.groupValues[1] }.toSet()
}

private fun runningPackages(): Set<String> {
    val output = runShell("ps -A -o NAME")
    return output.lineSequence().map { it.trim().substringBefore(':') }
        .filter { it.contains('.') && !it.startsWith("android.") }
        .toSet()
}

private fun runShell(command: String): String {
    return try {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        process.waitFor()
        output
    } catch (_: Exception) { "" }
}

private fun pinyinInitial(label: String): String {
    if (label.isBlank()) return "#"
    val first = label.trim().first()
    if (first.isDigit()) {
        val digit = listOf("LING", "YI", "ER", "SAN", "SI", "WU", "LIU", "QI", "BA", "JIU")[first.digitToInt()]
        return digit.first().toString()
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

private fun launchApp(context: Context, entry: AppEntry) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(entry.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {}
}

private fun loadSet(context: Context, key: String): Set<String> =
    context.getSharedPreferences("app_manager", Context.MODE_PRIVATE).getStringSet(key, emptySet())?.toSet() ?: emptySet()

private fun saveSet(context: Context, key: String, value: Set<String>) {
    context.getSharedPreferences("app_manager", Context.MODE_PRIVATE).edit().putStringSet(key, value.toSet()).apply()
}

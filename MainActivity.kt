package com.nagham.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Song(val title: String, val artist: String, val album: String, val link: String)
enum class Phase { Idle, Listening, Searching, Done, NotFound, Error }

private val Purple = Color(0xFF6C4CFF)
private val Cyan = Color(0xFF22C3FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val colors = if (dark) darkColorScheme(primary = Purple, background = Color(0xFF0D0E1A), surface = Color(0xFF171930))
            else lightColorScheme(primary = Purple, background = Color(0xFFF5F6FB), surface = Color.White)
            MaterialTheme(colorScheme = colors) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App() }
                }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.Idle) }
    var level by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<Song?>(null) }
    var tab by remember { mutableStateOf(0) }
    var history by remember { mutableStateOf(loadHistory(ctx)) }

    fun startListening() {
        if (phase == Phase.Listening || phase == Phase.Searching) return
        scope.launch {
            result = null
            phase = Phase.Listening
            val file = File(ctx.cacheDir, "rec.m4a")
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            try {
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioSamplingRate(44100)
                rec.setAudioEncodingBitRate(128000)
                rec.setOutputFile(file.absolutePath)
                rec.prepare(); rec.start()
                repeat(70) { delay(100); level = (rec.maxAmplitude / 16000f).coerceIn(0f, 1f) }
                rec.stop()
            } catch (e: Exception) {
                phase = Phase.Error; rec.release(); return@launch
            }
            rec.release(); level = 0f
            phase = Phase.Searching
            try {
                val song = withContext(Dispatchers.IO) { identify(file) }
                if (song == null) phase = Phase.NotFound else {
                    result = song; phase = Phase.Done
                    history = (listOf(song) + history.filter { it.title != song.title }).take(50)
                    saveHistory(ctx, history)
                }
            } catch (e: Exception) { phase = Phase.Error }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startListening() else phase = Phase.Error
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("🎵 نغمة", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("الرئيسية") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("السجل") })
        }
        if (tab == 0) Home(phase, level, result, onTap = {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }, onOpen = { open(ctx, it.link) })
        else History(history) { open(ctx, it.link) }
    }
}

@Composable
fun ColumnScope.Home(phase: Phase, level: Float, result: Song?, onTap: () -> Unit, onOpen: (Song) -> Unit) {
    val inf = rememberInfiniteTransition(label = "pulse")
    val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "p")
    val listening = phase == Phase.Listening
    val hint = when (phase) {
        Phase.Idle -> "اضغط لتعرف الأغنية"
        Phase.Listening -> "جارٍ الاستماع…"
        Phase.Searching -> "جارٍ البحث…"
        Phase.Done -> "تم التعرّف!"
        Phase.NotFound -> "لم أتعرف على الأغنية، جرّب مرة أخرى"
        Phase.Error -> "تعذّر التنفيذ. تأكد من الميكروفون والإنترنت"
    }
    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(hint, fontSize = 17.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .65f))
        Spacer(Modifier.height(32.dp))
        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            if (listening) Canvas(Modifier.fillMaxSize()) {
                for (off in listOf(0f, .5f)) {
                    val q = (p + off) % 1f
                    drawCircle(Purple.copy(alpha = (1f - q) * .35f), radius = size.minDimension / 2 * (.68f + q * .32f))
                }
            }
            Box(
                Modifier.size((160 + level * 24).dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Purple, Cyan)))
                    .clickable(enabled = phase != Phase.Listening && phase != Phase.Searching) { onTap() },
                contentAlignment = Alignment.Center
            ) {
                if (phase == Phase.Searching) CircularProgressIndicator(color = Color.White)
                else Text("🎤", fontSize = 56.sp)
            }
        }
        Spacer(Modifier.height(32.dp))
        if (phase == Phase.Done && result != null) SongCard(result, onOpen, big = true)
    }
}

@Composable
fun SongCard(s: Song, onOpen: (Song) -> Unit, big: Boolean = false) {
    Card(
        Modifier.fillMaxWidth().clickable { onOpen(s) },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(if (big) 72.dp else 56.dp).clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(Purple, Cyan))),
                contentAlignment = Alignment.Center
            ) { Text("♪", color = Color.White, fontSize = 28.sp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, fontSize = if (big) 19.sp else 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(s.artist, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), maxLines = 1)
            }
            Text("▶", fontSize = 20.sp, color = Purple)
        }
    }
}

@Composable
fun History(list: List<Song>, onOpen: (Song) -> Unit) {
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("لا توجد أغانٍ بعد") }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list) { SongCard(it, onOpen) }
    }
}

fun open(ctx: Context, link: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
}

private val http = OkHttpClient()

fun identify(file: File): Song? {
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("api_token", BuildConfig.AUDD_TOKEN)
        .addFormDataPart("file", "rec.m4a", file.asRequestBody("audio/mp4".toMediaType()))
        .build()
    val req = Request.Builder().url("https://api.audd.io/").post(body).build()
    http.newCall(req).execute().use { r ->
        val json = JSONObject(r.body?.string() ?: return null)
        if (json.optString("status") != "success") throw Exception(json.optString("error"))
        val res = json.optJSONObject("result") ?: return null
        val title = res.optString("title")
        val artist = res.optString("artist")
        return Song(title, artist, res.optString("album"),
            res.optString("song_link").ifEmpty { "https://www.youtube.com/results?search_query=" + Uri.encode("$title $artist") })
    }
}

fun loadHistory(ctx: Context): List<Song> = try {
    val arr = JSONArray(ctx.getSharedPreferences("h", 0).getString("l", "[]"))
    (0 until arr.length()).map { val o = arr.getJSONObject(it); Song(o.getString("t"), o.getString("a"), o.optString("b"), o.getString("l")) }
} catch (_: Exception) { emptyList() }

fun saveHistory(ctx: Context, list: List<Song>) {
    val arr = JSONArray()
    list.forEach { arr.put(JSONObject().put("t", it.title).put("a", it.artist).put("b", it.album).put("l", it.link)) }
    ctx.getSharedPreferences("h", 0).edit().putString("l", arr.toString()).apply()
}

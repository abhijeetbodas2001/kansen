package `in`.kansen.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

class UpdateFileProvider : FileProvider()

private data class AppRelease(val version: String, val url: String, val size: Long, val digest: String)

private object AppUpdates {
    private const val repository = "https://api.github.com/repos/abhijeetbodas2001/kansen/releases/latest"
    private fun connection(url: String) = (URL(url).openConnection() as HttpsURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Kansen")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun version(value: String): List<Int> {
        require(Regex("v?\\d+\\.\\d+\\.\\d+").matches(value))
        return value.removePrefix("v").split('.').map { it.toInt() }
    }

    suspend fun check(context: Context): AppRelease? = withContext(Dispatchers.IO) {
        val conn = connection(repository)
        val release = try {
            check(conn.responseCode == 200)
            conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally { conn.disconnect() }
        check(!release.getBoolean("draft") && !release.getBoolean("prerelease"))
        val tag = release.getString("tag_name")
        val latest = version(tag)
        val installed = version(context.packageManager.getPackageInfo(context.packageName, 0).versionName!!)
        val difference = latest.zip(installed).firstOrNull { (a, b) -> a != b }
        if (difference == null || difference.first < difference.second) return@withContext null
        val assets = release.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .single { it.getString("name") == "kansen-v${tag.removePrefix("v")}.apk" }
        val url = apk.getString("browser_download_url")
        check(url.startsWith("https://github.com/abhijeetbodas2001/kansen/releases/download/"))
        val digest = apk.getString("digest").removePrefix("sha256:")
        check(Regex("[a-fA-F0-9]{64}").matches(digest))
        AppRelease(tag.removePrefix("v"), url, apk.getLong("size"), digest)
    }

    @Suppress("DEPRECATION")
    suspend fun download(context: Context, release: AppRelease, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        check(release.size in 1..200_000_000L)
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File.createTempFile("update-", ".part", directory)
        val apk = File(directory, partial.nameWithoutExtension + ".apk")
        val conn = connection(release.url)
        try {
            check(conn.responseCode == 200)
            val hash = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= release.size)
                        output.write(buffer, 0, count)
                        hash.update(buffer, 0, count)
                        val percent = (total * 100 / release.size).toInt()
                        if (percent != lastPercent) {
                            withContext(Dispatchers.Main) { progress(total.toFloat() / release.size) }
                            lastPercent = percent
                        }
                    }
                }
            }
            check(total == release.size)
            check(hash.digest().joinToString("") { "%02x".format(it) }.equals(release.digest, ignoreCase = true))
            val pm = context.packageManager
            val candidate = checkNotNull(pm.getPackageArchiveInfo(partial.path, PackageManager.GET_SIGNATURES))
            val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            check(candidate.packageName == context.packageName && candidate.versionCode > installed.versionCode)
            check(candidate.versionName == release.version)
            val signatures = candidate.signatures?.map { it.toCharsString() }?.toSet()
            check(!signatures.isNullOrEmpty() && signatures == installed.signatures?.map { it.toCharsString() }?.toSet())
            currentCoroutineContext().ensureActive()
            apk.delete()
            check(partial.renameTo(apk))
            apk
        } finally {
            conn.disconnect()
            partial.delete()
        }
    }

    // Also runs after a successful app update, clearing the installer file on next launch.
    @Suppress("DEPRECATION")
    fun cleanCache(context: Context) {
        val current = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        File(context.cacheDir, "updates").listFiles()?.forEach { file ->
            val installed = file.extension == "apk" &&
                (context.packageManager.getPackageArchiveInfo(file.path, 0)?.versionCode ?: Int.MAX_VALUE) <= current
            if (installed || System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000L) file.delete()
        }
    }
}

@Composable
internal fun UpdateSettingsRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("checking") }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun install() {
        try {
            val apk = checkNotNull(downloaded)
            check(apk.exists())
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            open = false
        } catch (_: Exception) { stage = "error" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else stage = "permission"
    }
    fun dismiss() {
        job?.cancel()
        downloaded?.delete()
        downloaded = null
        open = false
    }
    SettingsRow(title = stringResource(R.string.check_updates), onClick = {
        open = true
        stage = "checking"
        release = null
        downloaded = null
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { AppUpdates.cleanCache(context) }
                release = AppUpdates.check(context)
                stage = if (release == null) "current" else "available"
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { stage = "error" }
        }
    })
    if (open) AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text(stringResource(R.string.check_updates)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(when (stage) {
                    "checking" -> stringResource(R.string.update_checking)
                    "current" -> stringResource(R.string.update_current)
                    "available" -> stringResource(R.string.update_available, release!!.version)
                    "downloading" -> stringResource(R.string.update_downloading, (progress * 100).toInt())
                    "ready", "permission" -> stringResource(R.string.update_install_permission)
                    else -> stringResource(R.string.update_error)
                })
                if (stage == "checking") LinearProgressIndicator(Modifier.fillMaxWidth())
                if (stage == "downloading") LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            if (stage == "available") TextButton(onClick = {
                stage = "downloading"
                progress = 0f
                job = scope.launch {
                    try {
                        downloaded = AppUpdates.download(context, release!!) { progress = it }
                        stage = "ready"
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { stage = "error" }
                }
            }) { Text(stringResource(R.string.update_download)) }
            if (stage == "ready" || stage == "permission") TextButton(onClick = {
                if (context.packageManager.canRequestPackageInstalls()) install()
                else try {
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                } catch (_: Exception) { stage = "error" }
            }) { Text(stringResource(R.string.update_install)) }
        },
        dismissButton = { TextButton(onClick = { dismiss() }) { Text(stringResource(R.string.update_close)) } },
    )
}

internal fun cleanUpdateCache(context: Context) = AppUpdates.cleanCache(context)

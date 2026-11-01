package pk.advocate.casediary.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pk.advocate.casediary.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer build than the one installed, and can
 * download + launch its install prompt — so getting the latest APK is a
 * button inside the app instead of a trip back to ask for a fresh link.
 *
 * There is no Play Store distribution here, so Android will not silently
 * auto-install anything on its own; this gets as close as a sideloaded app
 * is allowed to: the app itself finds, downloads and hands the new APK to
 * the system installer, and the user taps through Android's own confirm
 * screen (the same screen any APK install shows).
 */
object UpdateChecker {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val notes: String
    )

    /** @return the latest release's info, or null if none was found / the check failed. */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "CauselistSentinel-UpdateChecker")
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()

            val root = JSONObject(body)
            val tag = root.optString("tag_name")
            val versionCode = tag.trim().trimStart('v', 'V').toIntOrNull() ?: return@withContext null
            val versionName = root.optString("name").ifBlank { tag }
            val notes = root.optString("body")

            val assets = root.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    break
                }
            }
            val downloadUrl = apkUrl ?: return@withContext null
            UpdateInfo(versionCode, versionName, downloadUrl, notes)
        } catch (_: Exception) {
            null
        }
    }

    fun isNewer(info: UpdateInfo): Boolean = info.versionCode > BuildConfig.VERSION_CODE

    /** Downloads the release APK into the app's own cache. Runs on the IO dispatcher. */
    suspend fun download(context: Context, info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared")
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, "causelist-sentinel-update.apk")
        val conn = URL(info.downloadUrl).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "CauselistSentinel-UpdateChecker")
        conn.inputStream.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        conn.disconnect()
        out
    }

    /** True once the user has allowed this app to install other packages (always true before API 26). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Sends the user to the system screen where they allow this app to install unknown apps. */
    fun requestInstallPermission(context: Context) {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }

    /** Hands the downloaded APK to the system installer — the same confirm screen any APK install shows. */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /**
     * The full "there's a newer build" flow, shared by the silent check on app
     * start and the manual "Check for updates" button in Settings: confirm,
     * download, then either install or — the first time — send the user to
     * allow "install unknown apps" for this app before trying again.
     */
    fun promptInstall(context: Context, scope: CoroutineScope, rootView: View, info: UpdateInfo) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Update available — ${info.versionName}")
            .setMessage(info.notes.ifBlank { "A newer build is ready to install." })
            .setPositiveButton("Update now") { _, _ ->
                scope.launch {
                    Snackbar.make(rootView, "Downloading update…", Snackbar.LENGTH_INDEFINITE).show()
                    val file = try {
                        download(context, info)
                    } catch (e: Exception) {
                        Snackbar.make(
                            rootView, "Download failed: ${e.message ?: "unknown error"}", Snackbar.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    if (!canInstall(context)) {
                        Snackbar.make(
                            rootView,
                            "Allow \"install unknown apps\" for this app, then tap Update again.",
                            Snackbar.LENGTH_LONG
                        ).show()
                        requestInstallPermission(context)
                        return@launch
                    }
                    Snackbar.make(rootView, "Downloaded — opening installer…", Snackbar.LENGTH_SHORT).show()
                    install(context, file)
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }
}

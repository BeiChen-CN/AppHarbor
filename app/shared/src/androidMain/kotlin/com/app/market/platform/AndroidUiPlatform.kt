package com.app.market.platform

import android.content.ComponentName
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AndroidUiPlatform(
    private val context: Context,
    private val permissions: AndroidPermissionCoordinator,
) : UiPlatform {
    override val packageInstallationSupported: Boolean = true

    // 仅比较剪贴板描述的时间戳：读描述不会触发系统「已粘贴」提示，内容没变就不读正文
    private var lastClipTimestamp = -1L

    override suspend fun readNewClipboardText(): String? {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        val description = clipboard.primaryClipDescription ?: return null
        if (!description.hasMimeType("text/*")) return null
        if (description.timestamp == lastClipTimestamp) return null
        lastClipTimestamp = description.timestamp
        return clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }

    override fun shareText(text: String): ShareResult {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { context.startActivity(chooser) }.isSuccess) ShareResult.Shared else ShareResult.Failed
    }

    override fun showToast(message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun openAppSettings(): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    override fun openUnknownSourcesSettings(): Boolean {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return true

        // Some vendor ROMs do not expose the per-app unknown-sources activity.
        // Keep the permission dialog useful by falling back to this app's settings.
        return openAppSettings()
    }

    override fun openInOtherAppStore(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=${Uri.encode(packageName)}"),
        )
        val targets = queryIntentActivities(marketIntent)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName to it.activityInfo.name }
            .map { target ->
                Intent(marketIntent)
                    .setComponent(ComponentName(target.activityInfo.packageName, target.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            .toList()
        if (targets.isEmpty()) return false

        val launchIntent = if (targets.size == 1) {
            targets.single()
        } else {
            Intent.createChooser(targets.first(), null)
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.drop(1).toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(launchIntent) }.isSuccess
    }

    override fun requestInstalledAppsPermission(onResult: (Boolean) -> Unit): Boolean =
        permissions.requestInstalledApps(onResult)

    override fun requestPostNotificationsPermission(onResult: () -> Unit): Boolean =
        permissions.requestNotifications(onResult)

    override suspend fun saveImageToPictures(url: String, fileName: String): ImageSaveResult =
        withContext(Dispatchers.IO) {
            runCatching { saveImage(url, fileName) }.getOrDefault(ImageSaveResult.Failed)
        }

    private fun saveImage(url: String, fileName: String): ImageSaveResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            connection.inputStream.use { input ->
                val mime = connection.contentType?.substringBefore(";")?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                val extension = imageExtension(url, mime)
                val displayName = "${sanitizeFileName(fileName)}.$extension"
                val resolver = context.contentResolver
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, mime)
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Market")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("Unable to create image file")
                    try {
                        resolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                            ?: error("Unable to write image file")
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    } catch (error: Throwable) {
                        runCatching { resolver.delete(uri, null, null) }
                        throw error
                    }
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Market")
                    dir.mkdirs()
                    File(dir, displayName).outputStream().use { output -> input.copyTo(output) }
                }
            }
        } finally {
            connection.disconnect()
        }
        return ImageSaveResult.Saved
    }

    private fun sanitizeFileName(value: String): String =
        value.ifBlank { "image" }.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').ifBlank { "image" }

    private fun imageExtension(url: String, mime: String): String {
        val fromUrl = url.substringBefore("?").substringAfterLast('.', "").lowercase()
        if (fromUrl in setOf("jpg", "jpeg", "png", "webp", "gif")) return if (fromUrl == "jpeg") "jpg" else fromUrl
        return when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
    }

    private fun queryIntentActivities(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
}

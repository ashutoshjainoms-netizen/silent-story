package com.ashu.ghostview

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Saves media to Pictures/GhostView, or shares it (for reposting) through the share sheet. */
object Saver {
    private fun fileName(name: String, isVideo: Boolean) =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_") + if (isVideo) ".mp4" else ".jpg"

    fun download(ctx: Context, url: String, isVideo: Boolean, name: String) {
        try {
            val file = fileName(name, isVideo)
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(file)
                .setMimeType(if (isVideo) "video/mp4" else "image/jpeg")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, "GhostView/$file")
                .addRequestHeader("User-Agent", Ig.UA)
            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(ctx, "Saving to Pictures/GhostView", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun share(ctx: Context, url: String, isVideo: Boolean, name: String) {
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, fileName(name, isVideo))
                val c = URL(url).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", Ig.UA)
                c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                c.disconnect()
                out
            }
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = if (isVideo) "video/mp4" else "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "Share / repost"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

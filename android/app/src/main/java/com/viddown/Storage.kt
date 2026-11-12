package com.viddown

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

/**
 * Bitmiş dosyayı herkese açık depolamaya taşır.
 *  - Varsayılan: Download/ViDDoWN (MediaStore; izin gerektirmez)
 *  - Özel klasör: Ayarlar'da seçilen SAF klasörü
 * yt-dlp önce uygulamanın geçici klasörüne indirir; bu sayede kapsamlı depolama (scoped storage)
 * kısıtlarına takılmadan yarım kalan indirmeler de devam ettirilebilir.
 */
object Storage {
    const val ROOT = "ViDDoWN"
    private const val EXTERNAL_DOCS = "com.android.externalstorage.documents"

    data class Saved(val fileUri: Uri, val name: String, val folderLabel: String, val folderUri: Uri?)

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> "application/octet-stream"
    }

    @Throws(IOException::class)
    fun export(ctx: Context, file: File, cfg: Config, playlist: String?): Saved =
        if (cfg.saveTreeUri.isBlank()) exportToDownloads(ctx, file, playlist)
        else exportToTree(ctx, file, Uri.parse(cfg.saveTreeUri), playlist)

    private fun exportToDownloads(ctx: Context, file: File, playlist: String?): Saved {
        val rel = Environment.DIRECTORY_DOWNLOADS + "/" + ROOT +
            (playlist?.let { "/" + safeFileName(it) } ?: "")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(file.name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("İndirilenler klasörüne dosya oluşturulamadı.")
        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                file.inputStream().use { it.copyTo(out, 1 shl 20) }
            } ?: throw IOException("Hedef dosya açılamadı.")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }   // yarım dosyayı temizle
            throw if (t is IOException) t else IOException(t.message, t)
        }
        val folder = DocumentsContract.buildDocumentUri(EXTERNAL_DOCS, "primary:$rel")
        return Saved(uri, file.name, rel, folder)
    }

    private fun exportToTree(ctx: Context, file: File, tree: Uri, playlist: String?): Saved {
        val root = DocumentFile.fromTreeUri(ctx, tree)
        if (root == null || !root.exists() || !root.canWrite()) {
            throw IOException("Seçili kayıt klasörüne yazılamıyor. Ayarlar'dan klasörü yeniden seçin.")
        }
        var dir: DocumentFile = root
        if (playlist != null) {
            val sub = safeFileName(playlist)
            dir = root.findFile(sub)?.takeIf { it.isDirectory }
                ?: root.createDirectory(sub)
                ?: throw IOException("Alt klasör oluşturulamadı: $sub")
        }
        val target = dir.createFile(mimeFor(file.name), file.name)
            ?: throw IOException("Hedef dosya oluşturulamadı.")
        try {
            ctx.contentResolver.openOutputStream(target.uri, "w")?.use { out ->
                file.inputStream().use { it.copyTo(out, 1 shl 20) }
            } ?: throw IOException("Hedef dosya açılamadı.")
        } catch (t: Throwable) {
            runCatching { target.delete() }
            throw if (t is IOException) t else IOException(t.message, t)
        }
        return Saved(target.uri, target.name ?: file.name, describeFolder(tree.toString()), dir.uri)
    }

    /** Ayarlar ekranında gösterilecek klasör adı. */
    fun describeFolder(saveTreeUri: String): String {
        if (saveTreeUri.isBlank()) return "Download/$ROOT"
        return try {
            val docId = DocumentsContract.getTreeDocumentId(Uri.parse(saveTreeUri))
            val path = docId.substringAfter(':', docId)
            if (path.isEmpty()) "Dahili depolama" else path
        } catch (_: Exception) {
            saveTreeUri
        }
    }

    /** Kaydedilmiş dosyayı ilgili uygulamada açar (oynatıcı vb.). */
    fun openFile(ctx: Context, item: DownloadItem): Boolean {
        if (item.savedUri.isBlank()) return false
        return try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(item.savedUri), mimeFor(item.savedName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(i)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** "Klasörde göster": Dosyalar uygulamasında klasörü açar; olmazsa İndirilenler listesini. */
    fun openFolder(ctx: Context, item: DownloadItem, cfg: Config): Boolean {
        val folder = when {
            item.folderUri.isNotBlank() -> Uri.parse(item.folderUri)
            cfg.saveTreeUri.isNotBlank() -> treeRoot(cfg.saveTreeUri)
            else -> DocumentsContract.buildDocumentUri(
                EXTERNAL_DOCS,
                "primary:" + Environment.DIRECTORY_DOWNLOADS + "/" + ROOT +
                    (item.playlist?.let { "/" + safeFileName(it) } ?: ""),
            )
        }
        try {
            if (folder != null) {
                val i = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(i)
                return true
            }
        } catch (_: Exception) {
        }
        return try {
            ctx.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun treeRoot(saveTreeUri: String): Uri? = try {
        val tree = Uri.parse(saveTreeUri)
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    } catch (_: Exception) {
        null
    }
}

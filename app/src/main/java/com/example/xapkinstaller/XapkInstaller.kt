package com.example.xapkinstaller

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** One APK inside the XAPK (base APK or a split such as a language or ABI split). */
data class SplitApk(val file: String, val id: String)

/** A non-APK data file (OBB expansion) that must be copied to Android/obb/<package>/. */
data class Expansion(val file: String, val fileName: String)

data class XapkManifest(
    val packageName: String,
    val name: String,
    val splitApks: List<SplitApk>,
    val expansions: List<Expansion>,
)

/** Thrown when the XAPK has OBB files but the user has not granted access to Android/obb yet. */
class ObbAccessRequired : Exception("This XAPK has data files that need access to Android/obb.")

object XapkInstaller {

    /** Copies the picked document into the app cache so it can be opened as a zip file. */
    fun copyToCache(context: Context, uri: Uri): File {
        val out = File(context.cacheDir, "pending.xapk")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open the selected file")
        input.use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
        return out
    }

    /** Reads manifest.json from the XAPK. Falls back to scanning for .apk files if it is missing. */
    fun readManifest(xapk: File): XapkManifest {
        ZipFile(xapk).use { zip ->
            val entry = zip.getEntry("manifest.json")
            if (entry == null) {
                // Not every XAPK has a manifest; treat every .apk in the archive as a split.
                val apks = zip.entries().toList()
                    .filter { it.name.endsWith(".apk") && !it.isDirectory }
                    .map { SplitApk(it.name, it.name.substringBeforeLast('.')) }
                require(apks.isNotEmpty()) { "Not a valid XAPK: no manifest.json and no APK files" }
                return XapkManifest(
                    packageName = "unknown",
                    name = xapk.nameWithoutExtension,
                    splitApks = apks,
                    expansions = emptyList(),
                )
            }

            val json = zip.getInputStream(entry).use { JSONObject(it.bufferedReader().readText()) }
            val packageName = json.getString("package_name")
            val name = json.optString("name", packageName)

            val splitsJson = json.optJSONArray("split_apks") ?: JSONArray()
            val splits = (0 until splitsJson.length()).map { i ->
                val o = splitsJson.getJSONObject(i)
                SplitApk(file = o.getString("file"), id = o.optString("id", ""))
            }
            require(splits.isNotEmpty()) { "manifest.json lists no APK files" }

            val expansionsJson = json.optJSONArray("expansions") ?: JSONArray()
            val expansions = (0 until expansionsJson.length()).map { i ->
                val o = expansionsJson.getJSONObject(i)
                val file = o.getString("file")
                // install_path looks like "Android/obb/<pkg>/main.1.<pkg>.obb"; keep just the file name.
                val installPath = o.optString("install_path", file)
                Expansion(file = file, fileName = installPath.substringAfterLast('/'))
            }

            return XapkManifest(packageName, name, splits, expansions)
        }
    }

    /**
     * Installs every APK in the XAPK as one PackageInstaller session, so split APKs
     * are installed together. Expansion (OBB) files are copied to [obbTreeUri] first.
     */
    fun install(context: Context, xapk: File, manifest: XapkManifest, obbTreeUri: Uri?) {
        if (manifest.expansions.isNotEmpty() && obbTreeUri == null) throw ObbAccessRequired()

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (manifest.packageName != "unknown") params.setAppPackageName(manifest.packageName)

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            ZipFile(xapk).use { zip ->
                for (split in manifest.splitApks) {
                    val entry = zip.getEntry(split.file)
                        ?: throw IllegalStateException("Missing ${split.file} inside the XAPK")
                    val name = split.file.substringAfterLast('/')
                    session.openWrite(name, 0, entry.size).use { out ->
                        zip.getInputStream(entry).use { it.copyTo(out) }
                        session.fsync(out)
                    }
                }

                if (obbTreeUri != null) {
                    for (exp in manifest.expansions) {
                        copyExpansion(context, zip, exp, manifest.packageName, obbTreeUri)
                    }
                }
            }

            val resultIntent = Intent(context, InstallResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                resultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    private fun copyExpansion(
        context: Context,
        zip: ZipFile,
        exp: Expansion,
        packageName: String,
        obbTreeUri: Uri,
    ) {
        val entry = zip.getEntry(exp.file) ?: throw IllegalStateException("Missing ${exp.file} inside the XAPK")
        val tree = DocumentFile.fromTreeUri(context, obbTreeUri)
            ?: throw IllegalStateException("Cannot open the Android/obb folder")
        val pkgDir = tree.findFile(packageName) ?: tree.createDirectory(packageName)
            ?: throw IllegalStateException("Cannot create the $packageName folder in Android/obb")

        pkgDir.findFile(exp.fileName)?.delete()
        val dest = pkgDir.createFile("application/octet-stream", exp.fileName)
            ?: throw IllegalStateException("Cannot create ${exp.fileName}")
        val out = context.contentResolver.openOutputStream(dest.uri)
            ?: throw IllegalStateException("Cannot write ${exp.fileName}")
        out.use { dst -> zip.getInputStream(entry).use { it.copyTo(dst) } }
    }
}

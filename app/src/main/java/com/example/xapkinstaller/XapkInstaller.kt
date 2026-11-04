package com.example.xapkinstaller

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** One APK inside the bundle (base APK or a split such as a language or ABI split). */
data class SplitApk(val file: String, val id: String)

/** A non-APK data file (OBB expansion) that must be copied to Android/obb/<package>/. */
data class Expansion(val file: String, val fileName: String)

data class XapkManifest(
    val packageName: String,
    val name: String,
    val versionName: String,
    val splitApks: List<SplitApk>,
    val expansions: List<Expansion>,
    val icon: Bitmap?,
    val totalSize: Long,
)

object XapkInstaller {

    /** Every ABI name Android uses in split APK names, e.g. config.arm64_v8a.apk. */
    private val ALL_ABIS = listOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86_64", "x86", "mips", "mips64")

    /** Copies the opened file into the app cache so it can be read as a zip. */
    fun copyToCache(context: Context, uri: Uri): File {
        context.cacheDir.listFiles()?.forEach { it.delete() }
        val out = File(context.cacheDir, "pending.bundle")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open the selected file")
        input.use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
        return out
    }

    /** Best-effort display name for a content:// or file:// URI. */
    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) return c.getString(0)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** True if the file is a plain single APK rather than a bundle. */
    fun isPlainApk(file: File): Boolean = ZipFile(file).use { it.getEntry("AndroidManifest.xml") != null }

    /**
     * Reads the bundle. Supports XAPK (manifest.json), APKS / SAI bundles (just APKs in a zip,
     * sometimes with meta.sai_v2.json) and plain single APKs.
     */
    fun readManifest(context: Context, file: File, fallbackName: String): XapkManifest {
        if (isPlainApk(file)) {
            val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
            val label = info?.applicationInfo?.let {
                it.sourceDir = file.path
                it.publicSourceDir = file.path
                context.packageManager.getApplicationLabel(it).toString()
            }
            return XapkManifest(
                packageName = info?.packageName ?: "unknown",
                name = label ?: fallbackName,
                versionName = info?.versionName ?: "",
                splitApks = listOf(SplitApk("__self__", "base")),
                expansions = emptyList(),
                icon = null,
                totalSize = file.length(),
            )
        }

        ZipFile(file).use { zip ->
            val icon = zip.getEntry("icon.png")?.let { e ->
                runCatching { zip.getInputStream(e).use { BitmapFactory.decodeStream(it) } }.getOrNull()
            }
            val entry = zip.getEntry("manifest.json")

            if (entry == null) {
                val apks = zip.entries().toList()
                    .filter { it.name.endsWith(".apk") && !it.isDirectory }
                    .map { SplitApk(it.name, it.name.substringAfterLast('/').substringBeforeLast('.')) }
                require(apks.isNotEmpty()) { "This file doesn't contain any APKs" }
                val meta = zip.getEntry("meta.sai_v2.json")?.let { e ->
                    runCatching { zip.getInputStream(e).use { JSONObject(it.bufferedReader().readText()) } }.getOrNull()
                }
                return XapkManifest(
                    packageName = meta?.optString("package")?.takeIf { it.isNotBlank() } ?: "unknown",
                    name = meta?.optString("label")?.takeIf { it.isNotBlank() } ?: fallbackName.substringBeforeLast('.'),
                    versionName = meta?.optString("version_name") ?: "",
                    splitApks = filterSplits(apks),
                    expansions = emptyList(),
                    icon = icon,
                    totalSize = file.length(),
                )
            }

            val json = zip.getInputStream(entry).use { JSONObject(it.bufferedReader().readText()) }
            val packageName = json.getString("package_name")

            val splitsJson = json.optJSONArray("split_apks") ?: JSONArray()
            var splits = (0 until splitsJson.length()).map { i ->
                val o = splitsJson.getJSONObject(i)
                SplitApk(file = o.getString("file"), id = o.optString("id", ""))
            }
            if (splits.isEmpty()) {
                // Older XAPKs have a single "<package>.apk" and no split list.
                splits = zip.entries().toList()
                    .filter { it.name.endsWith(".apk") && !it.isDirectory }
                    .map { SplitApk(it.name, it.name.substringBeforeLast('.')) }
            }
            require(splits.isNotEmpty()) { "This XAPK lists no APK files" }

            val expansionsJson = json.optJSONArray("expansions") ?: JSONArray()
            val expansions = (0 until expansionsJson.length()).map { i ->
                val o = expansionsJson.getJSONObject(i)
                val f = o.getString("file")
                val installPath = o.optString("install_path", f)
                Expansion(file = f, fileName = installPath.substringAfterLast('/'))
            }.toMutableList()
            // Some XAPKs ship an Android/obb folder without listing it in the manifest.
            if (expansions.isEmpty()) {
                zip.entries().toList()
                    .filter { !it.isDirectory && it.name.startsWith("Android/obb/") }
                    .forEach { expansions += Expansion(it.name, it.name.substringAfterLast('/')) }
            }

            return XapkManifest(
                packageName = packageName,
                name = json.optString("name", packageName),
                versionName = json.optString("version_name", ""),
                splitApks = filterSplits(splits),
                expansions = expansions,
                icon = icon,
                totalSize = file.length(),
            )
        }
    }

    /**
     * Drops ABI splits this phone can't use (e.g. an x86 split on an ARM phone), which
     * otherwise make the whole install fail. Language and screen-density splits are kept.
     */
    private fun filterSplits(splits: List<SplitApk>): List<SplitApk> {
        val deviceAbis = Build.SUPPORTED_ABIS.map { it.replace('-', '_') }
        val abiSplits = splits.filter { s -> abiOf(s) != null }
        if (abiSplits.isEmpty()) return splits

        // Keep only the split for the phone's preferred ABI that the bundle actually has.
        val best = deviceAbis.firstOrNull { abi -> abiSplits.any { abiOf(it) == abi } }
        return splits.filter { s ->
            val abi = abiOf(s)
            abi == null || abi == best
        }
    }

    private fun abiOf(split: SplitApk): String? {
        val name = (split.id.ifBlank { split.file.substringAfterLast('/') }).lowercase()
        return ALL_ABIS.firstOrNull { abi -> name.endsWith("config.$abi") || name.endsWith("config.$abi.apk") || name.contains("config.$abi.") }
    }

    fun obbRoot(): File = File(Environment.getExternalStorageDirectory(), "Android/obb")

    /** Fallback location when Android won't let us write to Android/obb. */
    fun downloadsObbDir(packageName: String): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "obb/$packageName")

    /** True if we can create and write files in Android/obb/<package>/ right now. */
    fun canWriteObb(packageName: String): Boolean = runCatching {
        val dir = File(obbRoot(), packageName)
        dir.mkdirs()
        val probe = File(dir, ".xapk-installer-probe")
        probe.writeText("ok")
        probe.delete()
        true
    }.getOrDefault(false)

    /** Copies the OBB game data files into [targetDir]. */
    fun copyExpansions(file: File, manifest: XapkManifest, targetDir: File, onProgress: (String) -> Unit) {
        if (!targetDir.isDirectory && !targetDir.mkdirs()) error("Can't create ${targetDir.path}")
        ZipFile(file).use { zip ->
            manifest.expansions.forEachIndexed { i, exp ->
                onProgress("Copying game data ${i + 1} of ${manifest.expansions.size}...")
                val entry = zip.getEntry(exp.file) ?: error("Missing ${exp.file} inside the file")
                val dest = File(targetDir, exp.fileName)
                val tmp = File(targetDir, exp.fileName + ".part")
                tmp.outputStream().use { out -> zip.getInputStream(entry).use { it.copyTo(out, 1 shl 20) } }
                dest.delete()
                if (!tmp.renameTo(dest)) error("Can't write ${dest.path}")
            }
        }
    }

    /** Installs every APK as one PackageInstaller session, so split APKs go in together. */
    fun installApks(
        context: Context,
        file: File,
        manifest: XapkManifest,
        onProgress: (String) -> Unit,
    ) {
        onProgress("Installing ${manifest.name}...")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (manifest.packageName != "unknown") params.setAppPackageName(manifest.packageName)
        params.setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
        if (Build.VERSION.SDK_INT >= 31) {
            // Skips the extra confirm screen for updates of apps this installer installed.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            if (manifest.splitApks.singleOrNull()?.file == "__self__") {
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
            } else {
                ZipFile(file).use { zip ->
                    for (split in manifest.splitApks) {
                        val entry = zip.getEntry(split.file) ?: error("Missing ${split.file} inside the file")
                        val name = split.file.substringAfterLast('/')
                        session.openWrite(name, 0, entry.size).use { out ->
                            zip.getInputStream(entry).use { it.copyTo(out) }
                            session.fsync(out)
                        }
                    }
                }
            }

            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }
}

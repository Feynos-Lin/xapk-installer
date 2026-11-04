package com.example.xapkinstaller

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("xapk_installer", MODE_PRIVATE) }

    // The XAPK the user picked, kept between the file step and the OBB folder step.
    private var pendingFile: File? = null
    private var pendingManifest: XapkManifest? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    private val pickObbFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            InstallStatus.message.value = "Cancelled: no Android/obb folder was chosen."
            return@registerForActivityResult
        }
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit().putString(KEY_OBB_TREE, uri.toString()).apply()
        startInstall()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If opened from a file manager with an .xapk, install that file right away.
        intent?.data?.takeIf { intent.action == Intent.ACTION_VIEW }?.let { onFilePicked(it) }

        setContent {
            MaterialTheme {
                val status by InstallStatus.message.collectAsState()
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("XAPK Installer", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(12.dp))
                        Text("Pick an .xapk file to install its app and split APKs.")
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { pickFile.launch(arrayOf("*/*")) }) {
                            Text("Choose .xapk file")
                        }
                        status?.let {
                            Spacer(Modifier.height(24.dp))
                            Text(it)
                        }
                    }
                }
            }
        }
    }

    private fun onFilePicked(uri: Uri) {
        lifecycleScope.launch {
            InstallStatus.message.value = "Reading file..."
            try {
                val file = withContext(Dispatchers.IO) { XapkInstaller.copyToCache(this@MainActivity, uri) }
                val manifest = withContext(Dispatchers.IO) { XapkInstaller.readManifest(file) }
                pendingFile = file
                pendingManifest = manifest

                if (manifest.expansions.isNotEmpty() && obbTree() == null) {
                    InstallStatus.message.value = "This app has data files. Choose the Android/obb folder."
                    pickObbFolder.launch(null)
                } else {
                    startInstall()
                }
            } catch (e: Exception) {
                InstallStatus.message.value = "Could not read this file: ${e.message}"
            }
        }
    }

    private fun startInstall() {
        val file = pendingFile ?: return
        val manifest = pendingManifest ?: return

        if (!packageManager.canRequestPackageInstalls()) {
            InstallStatus.message.value =
                "Allow this app to install unknown apps, then pick the file again."
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }

        lifecycleScope.launch {
            InstallStatus.message.value = "Installing ${manifest.name}..."
            try {
                withContext(Dispatchers.IO) {
                    XapkInstaller.install(this@MainActivity, file, manifest, obbTree())
                }
            } catch (e: ObbAccessRequired) {
                pickObbFolder.launch(null)
            } catch (e: Exception) {
                InstallStatus.message.value = "Install failed: ${e.message}"
            }
        }
    }

    private fun obbTree(): Uri? = prefs.getString(KEY_OBB_TREE, null)?.let(Uri::parse)

    companion object {
        private const val KEY_OBB_TREE = "obb_tree_uri"
    }
}

package com.example.xapkinstaller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private var pendingFile: File? = null

    /** Set when we sent the user to a settings screen and should continue when they come back. */
    private var resumeInstallOnReturn = false

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openFile(uri)
    }

    private val legacyStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) installNow()
            else InstallStatus.state.value = InstallState.Failed("Storage permission is needed to copy game data.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val state by InstallStatus.state.collectAsState()
                Surface(Modifier.fillMaxSize()) {
                    Screen(state)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Coming back from "Install unknown apps" or "All files access" settings.
        if (resumeInstallOnReturn) {
            resumeInstallOnReturn = false
            installNow()
        }
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW, Intent.ACTION_INSTALL_PACKAGE -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null) openFile(uri)
    }

    private fun openFile(uri: Uri) {
        lifecycleScope.launch {
            InstallStatus.state.value = InstallState.Working("Reading file...")
            try {
                val name = XapkInstaller.displayName(this@MainActivity, uri)
                val manifest = withContext(Dispatchers.IO) {
                    val file = XapkInstaller.copyToCache(this@MainActivity, uri)
                    pendingFile = file
                    XapkInstaller.readManifest(this@MainActivity, file, name)
                }
                InstallStatus.current = manifest
                InstallStatus.state.value = InstallState.Ready(manifest)
            } catch (e: Exception) {
                InstallStatus.state.value = InstallState.Failed("Couldn't read this file: ${e.message}")
            }
        }
    }

    /** Checks each permission in turn, sending the user to settings if needed, then installs. */
    private fun installNow() {
        val file = pendingFile ?: return
        val manifest = InstallStatus.current ?: return

        if (!packageManager.canRequestPackageInstalls()) {
            resumeInstallOnReturn = true
            InstallStatus.state.value = InstallState.Working("Turn on \"Allow from this source\", then come back.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }

        if (manifest.expansions.isNotEmpty() && !hasStorageAccess()) {
            if (Build.VERSION.SDK_INT >= 30) {
                resumeInstallOnReturn = true
                InstallStatus.state.value = InstallState.Working("Turn on \"Allow access to manage all files\" so game data can be copied, then come back.")
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                }.onFailure {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else {
                legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            return
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    XapkInstaller.install(this@MainActivity, file, manifest) { msg ->
                        InstallStatus.state.value = InstallState.Working(msg)
                    }
                }
            } catch (e: Exception) {
                InstallStatus.state.value = InstallState.Failed("Install failed: ${e.message}")
            }
        }
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun launchInstalledApp(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    @Composable
    private fun Screen(state: InstallState) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                InstallState.Idle -> {
                    Text("XAPK Installer", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(12.dp))
                    Text("Tap any .xapk, .apks or .apk file in your file manager or downloads, or choose one here.")
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { pickFile.launch(arrayOf("*/*")) }) { Text("Choose file") }
                }

                is InstallState.Working -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(state.message)
                }

                is InstallState.Ready -> {
                    AppCard(state.manifest)
                    Spacer(Modifier.height(24.dp))
                    Row {
                        OutlinedButton(onClick = { InstallStatus.state.value = InstallState.Idle; finishIfOpenedFromFile() }) {
                            Text("Cancel")
                        }
                        Spacer(Modifier.width(16.dp))
                        Button(onClick = { installNow() }) { Text("Install") }
                    }
                }

                is InstallState.Done -> {
                    state.manifest?.let { AppCard(it) }
                    Spacer(Modifier.height(16.dp))
                    Text("Installed.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(24.dp))
                    Row {
                        OutlinedButton(onClick = { InstallStatus.state.value = InstallState.Idle; finish() }) { Text("Done") }
                        val pkg = state.manifest?.packageName
                        if (pkg != null && pkg != "unknown") {
                            Spacer(Modifier.width(16.dp))
                            Button(onClick = { launchInstalledApp(pkg) }) { Text("Open") }
                        }
                    }
                }

                is InstallState.Failed -> {
                    Text(state.message)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { InstallStatus.state.value = InstallState.Idle }) { Text("OK") }
                }
            }
        }
    }

    private fun finishIfOpenedFromFile() {
        if (intent?.action != Intent.ACTION_MAIN) finish()
    }

    @Composable
    private fun AppCard(m: XapkManifest) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                m.icon?.let {
                    Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.width(16.dp))
                }
                Column {
                    Text(m.name, style = MaterialTheme.typography.titleLarge)
                    if (m.versionName.isNotBlank()) Text("Version ${m.versionName}")
                    if (m.packageName != "unknown") Text(m.packageName, style = MaterialTheme.typography.bodySmall)
                    val extras = buildList {
                        if (m.splitApks.size > 1) add("${m.splitApks.size} APK parts")
                        if (m.expansions.isNotEmpty()) add("includes game data")
                        add("%.1f MB".format(m.totalSize / 1_048_576.0))
                    }
                    Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

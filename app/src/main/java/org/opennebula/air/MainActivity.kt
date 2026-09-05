package org.opennebula.air

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import org.opennebula.air.logging.LogTags
import org.opennebula.air.mirror.MirrorProjectionService
import org.opennebula.air.ui.ConnectScreen
import org.opennebula.air.ui.GlassesViewModel
import org.opennebula.air.ui.theme.OpenNebulaTheme

class MainActivity : ComponentActivity() {
    private val viewModel: GlassesViewModel by viewModels()

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            MirrorProjectionService.start(this) {
                viewModel.onMirrorPermission(this, result.resultCode, data)
            }
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { launchScreenCapture() }

    private val audioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.i(LogTags.UI, "MainActivity created")
        setContent {
            OpenNebulaTheme {
                ConnectScreen(viewModel)
            }
        }
        viewModel.startMirrorCapture = { requestMirror() }
        viewModel.requestBrowserAudio = { requestBrowserAudio() }
        maybeConnectFromAttach(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.bindDisplay(this)
    }

    override fun onPause() {
        viewModel.onActivityPause()
        super.onPause()
    }

    override fun onDestroy() {
        viewModel.startMirrorCapture = null
        viewModel.requestBrowserAudio = null
        viewModel.stopMirroring()
        viewModel.unbindDisplay()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeConnectFromAttach(intent)
    }

    private fun requestBrowserAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestMirror() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchScreenCapture()
    }

    private fun launchScreenCapture() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mgr.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mgr.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun maybeConnectFromAttach(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        Log.i(LogTags.USB, "USB_DEVICE_ATTACHED — starting connect()")
        viewModel.connect()
    }
}

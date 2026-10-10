package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.ui.JarvisScreen
import com.example.ui.JarvisViewModel
import com.example.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {

    private val viewModel: JarvisViewModel by viewModels()

    private val permissionsLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
            val contactsGranted = permissions[Manifest.permission.READ_CONTACTS] == true

            if (micGranted) {
                viewModel.onMicrophonePermissionGranted()
            } else {
                Toast.makeText(
                    this,
                    "Microphone access is needed for voice conversations.",
                    Toast.LENGTH_SHORT
                ).show()
                viewModel.onMicrophonePermissionDenied()
            }

            if (contactsGranted) {
                viewModel.addLog("PERM", "Contacts permission granted")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Request runtime permissions on first launch if not yet granted
        checkAndRequestPermissions()

        setContent {
            JarvisTheme {
                JarvisScreen(
                    viewModel = viewModel,
                    onRequestPermissions = { checkAndRequestPermissions() }
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            viewModel.onMicrophonePermissionGranted()
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.onMicrophonePermissionConfirmed()
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopJarvis()
    }
}

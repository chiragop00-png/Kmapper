package com.kmapper.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.kmapper.app.databinding.ActivityMainBinding
import com.kmapper.app.model.MappingProfile

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: ProfileStorage

    private val pickAppLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val pkg = result.data?.getStringExtra(AppPickerActivity.RESULT_PACKAGE_NAME) ?: return@registerForActivityResult
            val label = result.data?.getStringExtra(AppPickerActivity.RESULT_APP_LABEL) ?: pkg
            if (storage.load(pkg) == null) {
                storage.save(MappingProfile(profileName = pkg, gameName = label, packageName = pkg))
            }
            refreshList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        storage = ProfileStorage(this)

        refreshList()

        binding.btnNewProfile.text = "Add Game"
        binding.btnNewProfile.setOnClickListener {
            pickAppLauncher.launch(Intent(this, AppPickerActivity::class.java))
        }

        binding.btnEditSelected.text = "Fix Devices"
        binding.btnEditSelected.setOnClickListener {
            startActivity(Intent(this, DeviceScanActivity::class.java))
        }

        binding.btnStart.setOnClickListener {
            val profile = selectedProfile() ?: run {
                Toast.makeText(this, "Add and select a game first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
                return@setOnClickListener
            }
            // Launch the actual game first so it's what's on screen.
            val launchIntent = packageManager.getLaunchIntentForPackage(profile.packageName)
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "Couldn't find a launcher for ${profile.gameName}, is it still installed?", Toast.LENGTH_LONG).show()
            }
            // Then bring up capture + the floating toolbar on top of it.
            startForegroundService(Intent(this, KeyCaptureService::class.java).apply {
                putExtra(KeyCaptureService.EXTRA_PROFILE_NAME, profile.profileName)
            })
            Toast.makeText(this, "Starting KMapper for ${profile.gameName}. Accept the root prompt if asked.", Toast.LENGTH_LONG).show()
        }

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, KeyCaptureService::class.java))
            stopService(Intent(this, OverlayEditorService::class.java))
            Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show()
        }

        binding.btnDelete.setOnClickListener {
            val profile = selectedProfile() ?: return@setOnClickListener
            storage.delete(profile.profileName)
            refreshList()
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
        Toast.makeText(this, "Grant 'display over other apps' then hit Start again", Toast.LENGTH_LONG).show()
    }

    private fun selectedProfile(): MappingProfile? {
        val pos = binding.profileList.checkedItemPosition
        if (pos == ListView.INVALID_POSITION) return null
        val names = storage.listProfiles()
        val name = names.getOrNull(pos) ?: return null
        return storage.load(name)
    }

    private fun refreshList() {
        val profiles = storage.listProfiles().mapNotNull { storage.load(it) }
        val labels = profiles.map { "${it.gameName}  (${it.keyMappings.size} mapping${if (it.keyMappings.size == 1) "" else "s"})" }
        binding.profileList.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_single_choice, labels
        )
        binding.profileList.choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
    }

    override fun onResume() {
        super.onResume()
        refreshList() // mapping counts change while the floating editor is used
    }
}

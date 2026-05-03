package com.mesen2.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.mesen2.android.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val romList = mutableListOf<RomEntry>()
    private lateinit var adapter: ArrayAdapter<String>

    data class RomEntry(val name: String, val path: String)

    private val pickRomLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { launchRomFromUri(it) }
    }

    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { scanFolder(it) }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Storage permission required to browse ROMs", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, romList.map { it.name })
        binding.romListView.adapter = adapter

        binding.romListView.setOnItemClickListener { _, _, pos, _ ->
            launchRom(romList[pos].path)
        }

        binding.fabOpenRom.setOnClickListener {
            pickRomLauncher.launch(arrayOf(
                "application/octet-stream",
                "*/*"
            ))
        }

        // Initialize the Mesen2 core once
        val homeDir = File(filesDir, "MesenHome").also { it.mkdirs() }
        NativeLib.initialize(homeDir.absolutePath)

        checkPermissions()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_scan_folder -> {
                pickFolderLauncher.launch(null)
                true
            }
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun launchRomFromUri(uri: Uri) {
        // Copy ROM to internal cache so the C++ side can open it via a plain path
        val docFile = DocumentFile.fromSingleUri(this, uri) ?: return
        val name    = docFile.name ?: "rom.nes"
        val dest    = File(cacheDir, name)

        contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        launchRom(dest.absolutePath)
    }

    private fun launchRom(path: String) {
        val intent = Intent(this, EmulatorActivity::class.java)
        intent.putExtra(EmulatorActivity.EXTRA_ROM_PATH, path)
        startActivity(intent)
    }

    private fun scanFolder(uri: Uri) {
        val tree = DocumentFile.fromTreeUri(this, uri) ?: return
        romList.clear()

        fun scanRecursive(dir: DocumentFile) {
            for (file in dir.listFiles()) {
                if (file.isDirectory) {
                    scanRecursive(file)
                } else {
                    val n = file.name ?: continue
                    if (n.endsWith(".nes", ignoreCase = true) ||
                        n.endsWith(".fds", ignoreCase = true) ||
                        n.endsWith(".nsf", ignoreCase = true)) {
                        romList.add(RomEntry(n, file.uri.toString()))
                    }
                }
            }
        }

        scanRecursive(tree)
        romList.sortBy { it.name }
        adapter.clear()
        adapter.addAll(romList.map { it.name })
        adapter.notifyDataSetChanged()

        Toast.makeText(this, "Found ${romList.size} ROMs", Toast.LENGTH_SHORT).show()
    }

    private fun showSettingsDialog() {
        val filters = arrayOf(
            "None (native)", "NTSC (Blargg)", "NTSC (Bisqwit)",
            "xBRZ 2x", "xBRZ 3x", "xBRZ 4x", "xBRZ 5x", "xBRZ 6x",
            "HQ2x", "HQ3x", "HQ4x",
            "Scale2x", "Scale3x", "Scale4x",
            "2xSai", "Super 2xSai", "SuperEagle"
        )
        val filterIds = intArrayOf(
            NativeLib.FILTER_NONE, NativeLib.FILTER_NTSC, NativeLib.FILTER_BISQWIT_NTSC,
            NativeLib.FILTER_XBRZ_2X, NativeLib.FILTER_XBRZ_3X, NativeLib.FILTER_XBRZ_4X,
            NativeLib.FILTER_XBRZ_5X, NativeLib.FILTER_XBRZ_6X,
            NativeLib.FILTER_HQ2X, NativeLib.FILTER_HQ3X, NativeLib.FILTER_HQ4X,
            NativeLib.FILTER_SCALE2X, NativeLib.FILTER_SCALE3X, NativeLib.FILTER_SCALE4X,
            NativeLib.FILTER_2XSAI, NativeLib.FILTER_SUPER_2XSAI, NativeLib.FILTER_SUPER_EAGLE
        )
        val prefs = getSharedPreferences("mesen_prefs", MODE_PRIVATE)
        var selectedFilter = prefs.getInt("video_filter", 0)
        var hdPacks        = prefs.getBoolean("hd_packs", true)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setMultiChoiceItems(
                arrayOf("Enable HD Packs"),
                booleanArrayOf(hdPacks)
            ) { _, which, checked ->
                if (which == 0) hdPacks = checked
            }
            .setSingleChoiceItems(filters, selectedFilter) { _, which ->
                selectedFilter = which
            }
            .setPositiveButton("Apply") { _, _ ->
                prefs.edit()
                    .putInt("video_filter", selectedFilter)
                    .putBoolean("hd_packs", hdPacks)
                    .apply()
                NativeLib.setVideoFilter(filterIds[selectedFilter])
                NativeLib.setHdPacksEnabled(hdPacks)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT release the emulator here; it lives across activities.
        // Call NativeLib.release() only when the whole app process is exiting.
    }
}

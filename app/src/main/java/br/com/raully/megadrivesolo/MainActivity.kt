package br.com.raully.megadrivesolo

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : Activity() {
    private val pickRom = 61
    private lateinit var root: FrameLayout
    private lateinit var gameContainer: FrameLayout
    private lateinit var menu: LinearLayout
    private lateinit var status: TextView
    private var retroView: GLRetroView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        gameContainer = findViewById(R.id.gamecontainer)
        menu = findViewById(R.id.menu)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.openrom).setOnClickListener { openRomPicker() }
    }

    private fun openRomPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/zip", "application/x-genesis-rom"))
        }
        startActivityForResult(intent, pickRom)
    }

    @Deprecated("Deprecated by Android but retained for compatibility with the minimum API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickRom || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val rom = File(filesDir, "game_" + System.currentTimeMillis() + ".rom")
            contentResolver.openInputStream(uri)?.use { input -> rom.outputStream().use(input::copyTo) }
                ?: throw IllegalStateException("Não consegui ler o arquivo escolhido")
            launchGame(rom)
        } catch (e: Exception) {
            menu.visibility = View.VISIBLE
            status.text = "Erro ao abrir a ROM: ${e.message ?: "arquivo inválido"}"
        }
    }

    private fun launchGame(rom: File) {
        try {
            retroView?.let { lifecycle.removeObserver(it); gameContainer.removeView(it) }
            val core = File(applicationInfo.nativeLibraryDir, "libgenesis_plus_gx_libretro_android.so")
            if (!core.exists()) throw IllegalStateException("Núcleo ARM64 não veio no APK")
            val viewData = GLRetroViewData(this).apply {
                coreFilePath = core.absolutePath
                gameFilePath = rom.absolutePath
                systemDirectory = filesDir.absolutePath
                savesDirectory = File(filesDir, "saves").apply { mkdirs() }.absolutePath
                shader = ShaderConfig.Default
            }
            val view = GLRetroView(this, viewData)
            retroView = view
            lifecycle.addObserver(view)
            gameContainer.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            menu.visibility = View.GONE
            lifecycleScope.launch {
                view.getGLRetroErrors().flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED).collect { error ->
                    runOnUiThread {
                        menu.visibility = View.VISIBLE
                        status.text = "O core nativo não conseguiu abrir essa ROM (erro $error). Tente outro dump compatível."
                    }
                }
            }
        } catch (e: Exception) {
            menu.visibility = View.VISIBLE
            status.text = "Falha ao iniciar: ${e.message ?: "erro inesperado"}"
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        retroView?.sendKeyEvent(event.action, keyCode)
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        retroView?.sendKeyEvent(event.action, keyCode)
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y), 0)
        return super.onGenericMotionEvent(event)
    }
}

package br.com.raully.megadrivesolo

import androidx.activity.ComponentActivity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.Toast
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
import java.io.FileOutputStream
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import com.github.junrar.Archive

class MainActivity : ComponentActivity() {
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
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/zip", "application/x-rar-compressed", "application/x-7z-compressed", "application/x-genesis-rom"))
        }
        startActivityForResult(intent, pickRom)
    }

    @Deprecated("Deprecated by Android but retained for compatibility with the minimum API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickRom || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "jogo.bin"
            val downloaded = File(cacheDir, "download_${System.currentTimeMillis()}_${displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
            contentResolver.openInputStream(uri)?.use { input -> downloaded.outputStream().use { output -> input.copyTo(output) } }
                ?: throw IllegalStateException("Não consegui ler o arquivo escolhido")
            if (downloaded.length() < 512) throw IllegalArgumentException("O download tem ${downloaded.length()} bytes; isso parece incompleto, não uma ROM de Mega Drive.")
            val prefix = downloaded.inputStream().use { input -> ByteArray(8).also { input.read(it) } }
            val rom = when {
                isZip(prefix) || displayName.endsWith(".zip", true) -> extractZip(downloaded)
                isSevenZip(prefix) || displayName.endsWith(".7z", true) -> extract7z(downloaded)
                displayName.endsWith(".rar", true) || isRar(prefix) -> extractRar(downloaded)
                else -> {
                    if (looksLikeTextError(downloaded)) throw IllegalArgumentException("Esse arquivo parece ser uma página de erro HTML/texto, não o jogo. Baixe a ROM de novo como arquivo de jogo.")
                    val ext = displayName.substringAfterLast('.', "bin").lowercase()
                    val safeExt = if (ext in supportedRomExtensions) ext else "bin"
                    File(filesDir, "game_${System.currentTimeMillis()}.$safeExt").also { downloaded.copyTo(it, overwrite = true) }
                }
            }
            if (rom.length() < 8 * 1024) throw IllegalArgumentException("Depois da extração, achei só ${rom.length()} bytes. Uma ROM normal de Mega Drive costuma ser bem maior; o download parece incompleto ou o ZIP/7Z/RAR não é o jogo.")
            launchGame(rom)
        } catch (e: Exception) {
            menu.visibility = View.VISIBLE
            status.text = "Erro ao abrir a ROM: ${e.message ?: "arquivo inválido"}"
        }
    }

    private val supportedRomExtensions = setOf("bin", "gen", "md", "mdx", "smd", "sms", "gg", "sg", "68k", "sgd", "cue", "iso", "chd", "bms", "m3u")
    private val archiveRomExtensions = setOf("bin", "gen", "md", "mdx", "smd", "rom")
    private fun isArchiveGameEntry(name: String) = romExtension(name) in archiveRomExtensions
    private fun romOutputExtension(name: String) = if (romExtension(name) in supportedRomExtensions) romExtension(name) else "bin"

    private fun isZip(prefix: ByteArray) = prefix.size >= 4 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte()
    private fun isSevenZip(prefix: ByteArray) = prefix.size >= 6 && prefix[0] == 0x37.toByte() && prefix[1] == 0x7a.toByte() && prefix[2] == 0xbc.toByte() && prefix[3] == 0xaf.toByte() && prefix[4] == 0x27.toByte() && prefix[5] == 0x1c.toByte()
    private fun isRar(prefix: ByteArray) = prefix.size >= 7 && prefix[0] == 0x52.toByte() && prefix[1] == 0x61.toByte() && prefix[2] == 0x72.toByte() && prefix[3] == 0x21.toByte() && prefix[4] == 0x1a.toByte() && prefix[5] == 0x07.toByte()

    private fun looksLikeTextError(file: File): Boolean {
        val sample = file.inputStream().use { input -> ByteArray(512).also { input.read(it) } }
        val text = sample.toString(Charsets.UTF_8).trimStart().lowercase()
        return text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("{\"error") || text.startsWith("access denied")
    }

    private fun romExtension(name: String) = name.substringAfterLast('.', "").lowercase()

    private fun extractedRomFile(entryName: String): File {
        val ext = romOutputExtension(entryName)
        return File(filesDir, "game_${System.currentTimeMillis()}.$ext")
    }

    private fun extractZip(archive: File): File {
        ZipFile(archive).use { zip ->
            val entry = zip.entries().asSequence()
                .filter { !it.isDirectory && isArchiveGameEntry(it.name) }
                .firstOrNull() ?: throw IllegalArgumentException("Não encontrei ROM utilizável dentro do ZIP. Tente baixar de novo ou extrair e escolher o arquivo de jogo.")
            val rom = extractedRomFile(entry.name)
            zip.getInputStream(entry).use { input -> rom.outputStream().use { output -> input.copyTo(output) } }
            return rom
        }
    }

    private fun extractRar(archiveFile: File): File {
        Archive(archiveFile).use { archive ->
            while (true) {
                val header = archive.nextFileHeader() ?: break
                if (!header.isDirectory && isArchiveGameEntry(header.fileName)) {
                            if (header.isEncrypted) throw IllegalArgumentException("O RAR está protegido por senha. Extraia a ROM no celular e abra o arquivo de jogo diretamente.")
                    val rom = extractedRomFile(header.fileName)
                    archive.getInputStream(header).use { input -> rom.outputStream().use { output -> input.copyTo(output) } }
                    return rom
                }
            }
        }
        throw IllegalArgumentException("Não encontrei uma ROM compatível dentro desse RAR.")
    }

    private fun extract7z(archive: File): File {
        SevenZFile(archive).use { seven ->
            while (true) {
                val entry = seven.nextEntry ?: break
                if (!entry.isDirectory && isArchiveGameEntry(entry.name)) {
                    val rom = extractedRomFile(entry.name)
                    FileOutputStream(rom).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = seven.read(buffer)
                            if (count <= 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    return rom
                }
            }
        }
        throw IllegalArgumentException("Não encontrei ROM compatível dentro do 7Z.")
    }

    private fun launchGame(rom: File) {
        try {
            retroView?.let { lifecycle.removeObserver(it); gameContainer.removeView(it) }
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
                ?: throw IllegalStateException("Arquitetura Android não suportada neste APK: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            val core = File(applicationInfo.nativeLibraryDir, "libgenesis_plus_gx_libretro_android.so")
            if (!core.isFile || core.length() < 1_000_000) {
                val installed = File(applicationInfo.nativeLibraryDir).list()?.joinToString() ?: "nenhuma biblioteca nativa extraída"
                throw IllegalStateException("Core Genesis Plus GX não foi instalado para $abi. Bibliotecas encontradas: $installed")
            }
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
            showTouchControls(view)
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

    private fun showTouchControls(view: GLRetroView) {
        val left = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(12, 8, 12, 8); alpha = 0.78f }
        addTouchButton(left, "◀", KeyEvent.KEYCODE_DPAD_LEFT, view)
        addTouchButton(left, "▲", KeyEvent.KEYCODE_DPAD_UP, view)
        addTouchButton(left, "▼", KeyEvent.KEYCODE_DPAD_DOWN, view)
        addTouchButton(left, "▶", KeyEvent.KEYCODE_DPAD_RIGHT, view)
        val leftParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
        leftParams.bottomMargin = 12
        root.addView(left, leftParams)

        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(12, 8, 12, 8); alpha = 0.82f }
        addTouchButton(right, "A", KeyEvent.KEYCODE_BUTTON_A, view)
        addTouchButton(right, "B", KeyEvent.KEYCODE_BUTTON_B, view)
        addTouchButton(right, "C", KeyEvent.KEYCODE_BUTTON_X, view)
        addTouchButton(right, "START", KeyEvent.KEYCODE_BUTTON_START, view)
        val rightParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        rightParams.bottomMargin = 12
        root.addView(right, rightParams)
    }

    private fun addTouchButton(group: LinearLayout, label: String, keyCode: Int, view: GLRetroView) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = 48
            minHeight = 48
            setPadding(6, 0, 6, 0)
            contentDescription = label
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> view.sendKeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> view.sendKeyEvent(KeyEvent.ACTION_UP, keyCode)
                }
                true
            }
        }
        val params = LinearLayout.LayoutParams(56, 56)
        params.setMargins(3, 0, 3, 0)
        group.addView(button, params)
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

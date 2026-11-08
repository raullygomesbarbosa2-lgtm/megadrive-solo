package br.com.raully.megadrivesolo

import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.Intent
import android.provider.OpenableColumns
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.CheckBox
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private val pressedPointers = mutableMapOf<Int, Int>()
    private var touchDpadX = 0f
    private var touchDpadY = 0f
    private var switchingGame = false
    private val cheats = mutableListOf<CheatEntry>()
    private data class CheatEntry(val code: String, var enabled: Boolean = true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        hideSystemBars()
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        gameContainer = findViewById(R.id.gamecontainer)
        menu = findViewById(R.id.menu)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.openrom).setOnClickListener { openRomPicker() }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
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
        if (requestCode != pickRom || resultCode != RESULT_OK) {
            if (requestCode == pickRom && switchingGame) resumeCurrentGame()
            return
        }
        val uri = data?.data ?: run { if (switchingGame) resumeCurrentGame(); return }
        val wasSwitchingGame = switchingGame
        try {
            switchingGame = false
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
            if (wasSwitchingGame) cheats.clear()
            launchGame(rom)
        } catch (e: Exception) {
            if (wasSwitchingGame) resumeCurrentGame()
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
            retroView?.let { old ->
                lifecycle.removeObserver(old)
                runCatching { old.onPause() }
                runCatching { old.onDestroy() }
                gameContainer.removeView(old)
            }
            retroView = null
            root.findViewWithTag<View>("touch-controls")?.let(root::removeView)
            root.findViewWithTag<View>("touch-buttons")?.let(root::removeView)
            root.findViewWithTag<View>("game-menu")?.let(root::removeView)
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
            showGameMenuButton(view)
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

    private fun showGameMenuButton(view: GLRetroView) {
        val button = Button(this).apply {
            text = "☰"
            textSize = 22f
            contentDescription = "Menu do jogo"
            setOnClickListener { showGameMenu(view) }
        }
        val params = FrameLayout.LayoutParams((56 * resources.displayMetrics.density).toInt(), (52 * resources.displayMetrics.density).toInt(), Gravity.TOP or Gravity.END)
        params.setMargins(0, (12 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(), 0)
        button.tag = "game-menu"
        root.addView(button, params)
    }

    private fun showGameMenu(view: GLRetroView) {
        val items = arrayOf("Continuar jogo", "Trocar jogo/ROM", "Cheats")
        AlertDialog.Builder(this)
            .setTitle("Mega Drive Solo")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> Unit
                    1 -> pauseAndChooseRom()
                    2 -> showCheatsDialog(view)
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun pauseAndChooseRom() {
        switchingGame = true
        openRomPicker()
    }

    private fun resumeCurrentGame() {
        // Android pauses/resumes the native GL view around the document picker via Activity lifecycle.
        switchingGame = false
    }

    private fun showCheatsDialog(view: GLRetroView) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt(), (20 * resources.displayMetrics.density).toInt(), 0)
        }
        val codeInput = EditText(this).apply {
            hint = "GG: XXXX-XXXX  |  PAR: XXXXXX:XXXX"
            setSingleLine(true)
            textSize = 15f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        content.addView(TextView(this).apply {
            text = "Mega Drive: Game Genie XXXX-XXXX ou Pro Action Replay XXXXXX:XXXX. Digite o código correspondente ao jogo aberto."
            textSize = 12f
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        })
        content.addView(codeInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (cheats.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "Digite um código compatível com Genesis Plus GX. Compatibilidade varia conforme o formato do código."
                textSize = 13f
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
            })
        } else {
            val scroll = ScrollView(this)
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            cheats.forEachIndexed { index, cheat ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val toggle = CheckBox(this).apply {
                    text = cheat.code
                    isChecked = cheat.enabled
                    setOnCheckedChangeListener { _, checked ->
                        cheat.enabled = checked
                        view.setCheat(index, checked, cheat.code)
                    }
                }
                row.addView(toggle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val remove = Button(this).apply {
                    text = "Remover"
                    setOnClickListener {
                        cheats.forEachIndexed { oldIndex, oldCheat -> view.setCheat(oldIndex, false, oldCheat.code) }
                        cheats.removeAt(index)
                        cheats.forEachIndexed { newIndex, newCheat -> view.setCheat(newIndex, newCheat.enabled, newCheat.code) }
                        showCheatsDialog(view)
                    }
                }
                row.addView(remove)
                list.addView(row)
            }
            scroll.addView(list)
            content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (180 * resources.displayMetrics.density).toInt()))
        }
        AlertDialog.Builder(this)
            .setTitle("Cheats")
            .setView(content)
            .setPositiveButton("Adicionar") { _, _ ->
                val code = codeInput.text.toString().trim().replace(" ", "").uppercase()
                if (code.isEmpty()) return@setPositiveButton
                val valid = code.split('+').all { part ->
                    Regex("^[A-HJ-NP-Z0-9]{4}-[A-HJ-NP-Z0-9]{4}$", RegexOption.IGNORE_CASE).matches(part) ||
                    Regex("^[0-9A-F]{6}:[0-9A-F]{4}$", RegexOption.IGNORE_CASE).matches(part)
                }
                if (!valid) {
                    Toast.makeText(this, "Formato inválido. Use Game Genie XXXX-XXXX ou PAR XXXXXX:XXXX.", Toast.LENGTH_LONG).show()
                    showCheatsDialog(view)
                } else {
                    val index = cheats.size
                    cheats.add(CheatEntry(code))
                    runCatching { view.setCheat(index, true, code) }.onFailure {
                        cheats.removeAt(index)
                        Toast.makeText(this, "O core recusou esse código: ${it.message ?: "formato incompatível"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNeutralButton("Voltar", null)
            .setNegativeButton("Menu do jogo") { _, _ -> showGameMenu(view) }
            .show()
    }

    private fun showTouchControls(view: GLRetroView) {
        root.findViewWithTag<View>("touch-controls")?.let(root::removeView)
        val stick = VirtualJoystickView(this) { x, y ->
            val dpadX = when { x < -0.35f -> -1f; x > 0.35f -> 1f; else -> 0f }
            val dpadY = when { y < -0.35f -> -1f; y > 0.35f -> 1f; else -> 0f }
            touchDpadX = dpadX
            touchDpadY = dpadY
            view.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, dpadX, dpadY)
            view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, x, y)
        }.apply { tag = "touch-controls"; alpha = 0.82f }
        val stickSize = (168 * resources.displayMetrics.density).toInt()
        val stickParams = FrameLayout.LayoutParams(stickSize, stickSize, Gravity.BOTTOM or Gravity.START)
        stickParams.leftMargin = (18 * resources.displayMetrics.density).toInt()
        stickParams.bottomMargin = (16 * resources.displayMetrics.density).toInt()
        root.addView(stick, stickParams)

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(8, 6, 8, 6); alpha = 0.82f; tag = "touch-buttons" }
        root.findViewWithTag<View>("touch-buttons")?.let(root::removeView)
        // Mega Drive 6-button mapping onto libretro's standard RetroPad inputs.
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        addTouchButton(top, "X", KeyEvent.KEYCODE_BUTTON_L1, view)
        addTouchButton(top, "Y", KeyEvent.KEYCODE_BUTTON_X, view)
        addTouchButton(top, "Z", KeyEvent.KEYCODE_BUTTON_R1, view)
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        addTouchButton(bottom, "A", KeyEvent.KEYCODE_BUTTON_Y, view)
        addTouchButton(bottom, "B", KeyEvent.KEYCODE_BUTTON_B, view)
        addTouchButton(bottom, "C", KeyEvent.KEYCODE_BUTTON_A, view)
        right.addView(top)
        right.addView(bottom)
        val start = Button(this).apply {
            text = "START"
            setOnClickListener { view.sendKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_START); postDelayed({ view.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_START) }, 90L) }
        }
        right.addView(start, LinearLayout.LayoutParams((112 * resources.displayMetrics.density).toInt(), (42 * resources.displayMetrics.density).toInt()))
        val rightParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        rightParams.rightMargin = (12 * resources.displayMetrics.density).toInt()
        rightParams.bottomMargin = (16 * resources.displayMetrics.density).toInt()
        root.addView(right, rightParams)
    }

    private class VirtualJoystickView(context: android.content.Context, val onAxes: (Float, Float) -> Unit) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var axisX = 0f
        private var axisY = 0f
        private var activePointerId = MotionEvent.INVALID_POINTER_ID

        private fun releaseStick() {
            activePointerId = MotionEvent.INVALID_POINTER_ID
            axisX = 0f
            axisY = 0f
            onAxes(0f, 0f)
            invalidate()
        }

        private fun updateStick(x: Float, y: Float) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) * 0.43f
            val dx = (x - cx) / radius
            val dy = (y - cy) / radius
            val magnitude = kotlin.math.sqrt(dx * dx + dy * dy)
            val scale = if (magnitude > 1f) 1f / magnitude else 1f
            axisX = dx * scale
            axisY = dy * scale
            onAxes(axisX, axisY)
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) * 0.43f
            paint.color = 0x66343B55
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawCircle(cx, cy, radius, paint)
            paint.color = 0xCC77E3CD.toInt()
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 2.5f * density
            canvas.drawCircle(cx, cy, radius, paint)
            paint.color = 0xDDF2F4FA.toInt()
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawCircle(cx + axisX * radius * 0.68f, cy + axisY * radius * 0.68f, radius * 0.31f, paint)
            paint.color = 0xFF20253A.toInt()
            paint.textAlign = android.graphics.Paint.Align.CENTER
            paint.textSize = 12f * density
            canvas.drawText("JOYSTICK", cx, height - 5f * density, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    activePointerId = event.getPointerId(event.actionIndex)
                    updateStick(event.getX(event.actionIndex), event.getY(event.actionIndex))
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(activePointerId)
                    if (index >= 0) updateStick(event.getX(index), event.getY(index))
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == activePointerId) {
                        val nextIndex = if (event.actionIndex == 0) 1 else 0
                        if (nextIndex < event.pointerCount) {
                            activePointerId = event.getPointerId(nextIndex)
                            updateStick(event.getX(nextIndex), event.getY(nextIndex))
                        } else releaseStick()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseStick()
            }
            return true
        }
    }

    private fun addTouchButton(group: LinearLayout, label: String, keyCode: Int, view: GLRetroView) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = 48
            minHeight = 48
            setPadding(6, 0, 6, 0)
            contentDescription = "Botão $label"
            setOnTouchListener { _, event ->
                val pressActions = setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE)
                for (index in 0 until event.pointerCount) {
                    val isInside = event.getX(index) >= 0f && event.getX(index) < width && event.getY(index) >= 0f && event.getY(index) < height
                    val pointerKey = event.getPointerId(index)
                    val wasPressed = pressedPointers[pointerKey] == keyCode
                    val shouldPress = event.actionMasked in pressActions && isInside
                    if (shouldPress && !wasPressed) {
                        pressedPointers[pointerKey] = keyCode
                        view.sendKeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                    } else if (!shouldPress && wasPressed) {
                        pressedPointers.remove(pointerKey)
                        view.sendKeyEvent(KeyEvent.ACTION_UP, keyCode)
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    val pointerKey = event.getPointerId(event.actionIndex)
                    if (pressedPointers.remove(pointerKey) != null) view.sendKeyEvent(KeyEvent.ACTION_UP, keyCode)
                }
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    val stillHeld = pressedPointers.filterValues { it == keyCode }.keys.toList()
                    stillHeld.forEach { pressedPointers.remove(it) }
                    if (stillHeld.isNotEmpty()) view.sendKeyEvent(KeyEvent.ACTION_UP, keyCode)
                }
                true
            }
        }
        val size = (58 * resources.displayMetrics.density).toInt()
        val params = LinearLayout.LayoutParams(size, size)
        params.setMargins((3 * resources.displayMetrics.density).toInt(), (3 * resources.displayMetrics.density).toInt(), (3 * resources.displayMetrics.density).toInt(), (3 * resources.displayMetrics.density).toInt())
        group.addView(button, params)
    }

    private fun libretroKey(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> KeyEvent.KEYCODE_BUTTON_A
        KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_B
        KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_X
        KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_Y
        KeyEvent.KEYCODE_BUTTON_L1 -> KeyEvent.KEYCODE_BUTTON_L1
        KeyEvent.KEYCODE_BUTTON_R1 -> KeyEvent.KEYCODE_BUTTON_R1
        KeyEvent.KEYCODE_BUTTON_L2 -> KeyEvent.KEYCODE_BUTTON_L2
        KeyEvent.KEYCODE_BUTTON_R2 -> KeyEvent.KEYCODE_BUTTON_R2
        KeyEvent.KEYCODE_BUTTON_START -> KeyEvent.KEYCODE_BUTTON_START
        KeyEvent.KEYCODE_BUTTON_SELECT -> KeyEvent.KEYCODE_BUTTON_SELECT
        KeyEvent.KEYCODE_BUTTON_1, KeyEvent.KEYCODE_BUTTON_5 -> KeyEvent.KEYCODE_BUTTON_B
        KeyEvent.KEYCODE_BUTTON_2, KeyEvent.KEYCODE_BUTTON_6 -> KeyEvent.KEYCODE_BUTTON_A
        KeyEvent.KEYCODE_BUTTON_3 -> KeyEvent.KEYCODE_BUTTON_Y
        KeyEvent.KEYCODE_BUTTON_4 -> KeyEvent.KEYCODE_BUTTON_X
        KeyEvent.KEYCODE_BUTTON_7, KeyEvent.KEYCODE_BUTTON_8 -> KeyEvent.KEYCODE_BUTTON_START
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> keyCode
        else -> null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val mapped = libretroKey(event.keyCode)
        val gamepad = (event.source and (android.view.InputDevice.SOURCE_GAMEPAD or android.view.InputDevice.SOURCE_JOYSTICK)) != 0
        if (mapped != null && retroView != null && (gamepad || event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
            retroView?.sendKeyEvent(event.action, mapped)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isController = (event.source and (android.view.InputDevice.SOURCE_GAMEPAD or android.view.InputDevice.SOURCE_JOYSTICK)) != 0
        if (isController && retroView != null) {
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val leftX = event.getAxisValue(MotionEvent.AXIS_X)
            val leftY = event.getAxisValue(MotionEvent.AXIS_Y)
            val xAlt = event.getAxisValue(MotionEvent.AXIS_Z)
            val yAlt = event.getAxisValue(MotionEvent.AXIS_RZ)
            val useHat = kotlin.math.abs(hatX) > 0.2f || kotlin.math.abs(hatY) > 0.2f
            val chosenX = if (!useHat && kotlin.math.abs(xAlt) > kotlin.math.abs(leftX)) xAlt else leftX
            val chosenY = if (!useHat && kotlin.math.abs(yAlt) > kotlin.math.abs(leftY)) yAlt else leftY
            val dpadX = if (kotlin.math.abs(hatX) > 0.2f) hatX else when { chosenX < -0.45f -> -1f; chosenX > 0.45f -> 1f; else -> 0f }
            val dpadY = if (kotlin.math.abs(hatY) > 0.2f) hatY else when { chosenY < -0.45f -> -1f; chosenY > 0.45f -> 1f; else -> 0f }
            val mergedX = if (kotlin.math.abs(touchDpadX) > 0.2f) touchDpadX else dpadX
            val mergedY = if (kotlin.math.abs(touchDpadY) > 0.2f) touchDpadY else dpadY
            retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, mergedX, mergedY, 0)
            retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, if (kotlin.math.abs(leftX) > 0.2f) leftX else 0f, if (kotlin.math.abs(leftY) > 0.2f) leftY else 0f, 0)
            retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ), 0)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

}

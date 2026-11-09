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
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
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
    private var switchingGame = false
    private val cheats = mutableListOf<CheatEntry>()
    private val specialButtonLabels = listOf("A", "B", "C", "X", "Y", "Z")
    private val specialButtonKeys = listOf(KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_R1)
    private var specialCombo = listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B)
    private var specialComboLabel = "A+B"
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
        window.statusBarColor = Color.rgb(2, 13, 23)
        window.navigationBarColor = Color.rgb(2, 13, 23)
        menu.setBackgroundColor(Color.rgb(2, 13, 23))
        status.setTextColor(0xFF8FEFFF.toInt())
        findViewById<Button>(R.id.openrom).apply {
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xFF06202C.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(2), 0xFF36E8FF.toInt())
            }
            elevation = dp(7).toFloat()
            setOnClickListener { openRomPicker() }
        }
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

    override fun onResume() {
        super.onResume()
        // Reapply immersive mode after returning from the document picker or a system dialog.
        hideSystemBars()
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
            root.findViewWithTag<View>("touch-dpad")?.let(root::removeView)
            root.findViewWithTag<View>("touch-buttons")?.let(root::removeView)
            root.findViewWithTag<View>("touch-actions")?.let(root::removeView)
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
                shader = ShaderConfig.CRT
            }
            val view = GLRetroView(this, viewData)
            retroView = view
            lifecycle.addObserver(view)
            gameContainer.removeAllViews()
            gameContainer.clipChildren = false
            gameContainer.clipToPadding = false
            // Stretch the 4:3 game image across the landscape screen to eliminate side bars.
            val screenAspect = resources.displayMetrics.widthPixels.toFloat() / resources.displayMetrics.heightPixels.toFloat()
            view.scaleX = screenAspect / (4f / 3f)
            gameContainer.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            gameContainer.addView(CrtGlassView(this), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            showTouchControls(view)
            showGameMenuButton(view)
            menu.visibility = View.GONE
            lifecycleScope.launch {
                view.getGLRetroEvents().flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED).collect { event ->
                    if (event == GLRetroView.GLRetroEvents.SurfaceCreated) {
                        runCatching { view.viewport = RectF(0f, 0f, 1f, 1f) }
                    }
                }
            }
            lifecycleScope.launch {
                var sixButtonSet = false
                view.getGLRetroEvents().flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED).collect { event ->
                    if (!sixButtonSet && (event == GLRetroView.GLRetroEvents.SurfaceCreated || event == GLRetroView.GLRetroEvents.FrameRendered)) {
                        // Genesis Plus GX requests RETRO_DEVICE_JOYPAD subclass 1 for a six-button pad.
                        runCatching { view.setControllerType(0, (2 shl 8) or 1) }
                        sixButtonSet = true
                    }
                }
            }
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
            setTextColor(0xFF39F3FF.toInt())
            background = neonCircle(0xFF39F3FF.toInt())
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            elevation = dp(8).toFloat()
            setOnClickListener { showGameMenu(view) }
        }
        val params = FrameLayout.LayoutParams((56 * resources.displayMetrics.density).toInt(), (52 * resources.displayMetrics.density).toInt(), Gravity.TOP or Gravity.END)
        params.setMargins(0, (12 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(), 0)
        button.tag = "game-menu"
        root.addView(button, params)
    }

    private fun showGameMenu(view: GLRetroView) {
        val items = arrayOf("Continuar jogo", "Trocar jogo/ROM", "Cheats", "Configurar botão especial")
        AlertDialog.Builder(this)
            .setTitle("Mega Drive Solo")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> Unit
                    1 -> pauseAndChooseRom()
                    2 -> showCheatsDialog(view)
                    3 -> configureSpecialCombo(view)
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun configureSpecialCombo(view: GLRetroView) {
        val selected = BooleanArray(specialButtonLabels.size) { index -> specialButtonKeys[index] in specialCombo }
        AlertDialog.Builder(this)
            .setTitle("Botão especial")
            .setMultiChoiceItems(specialButtonLabels.toTypedArray(), selected) { _, which, checked -> selected[which] = checked }
            .setPositiveButton("Salvar") { _, _ ->
                val keys = specialButtonLabels.indices.filter { selected[it] }.map { specialButtonKeys[it] }
                if (keys.isEmpty()) {
                    Toast.makeText(this, "Selecione pelo menos um botão", Toast.LENGTH_SHORT).show()
                } else {
                    specialCombo = keys
                    specialComboLabel = specialButtonLabels.indices.filter { selected[it] }.joinToString("+") { specialButtonLabels[it] }
                    updateSpecialButton()
                    Toast.makeText(this, "Botão especial: $specialComboLabel", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateSpecialButton() {
        val group = root.findViewWithTag<LinearLayout>("button-row-top") ?: return
        group.findViewWithTag<View>("special-button")?.let(group::removeView)
        val specialSize = dp(48)
        val special = Button(this).apply {
            tag = "special-button"
            text = "★"
            textSize = 20f
            contentDescription = "Especial $specialComboLabel"
            setTextColor(Color.WHITE)
            background = neonCircle(0xFFFFD45A.toInt())
            elevation = dp(9).toFloat()
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { retroView?.let { sendSpecial(it) } }
        }
        special.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> sendSpecialDown(retroView)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> sendSpecialUp(retroView)
            }
            true
        }
        val params = LinearLayout.LayoutParams(specialSize, specialSize)
        params.gravity = Gravity.CENTER
        params.topMargin = dp(3)
        group.addView(special, params)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun neonCircle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xCC07151E.toInt())
        setStroke(dp(2), color)
    }

    private fun sendSpecialDown(view: GLRetroView?) {
        view ?: return
        specialCombo.forEach { view.sendKeyEvent(KeyEvent.ACTION_DOWN, it) }
    }

    private fun sendSpecialUp(view: GLRetroView?) {
        view ?: return
        specialCombo.forEach { view.sendKeyEvent(KeyEvent.ACTION_UP, it) }
    }

    private fun sendSpecial(view: GLRetroView) {
        sendSpecialDown(view)
        view.postDelayed({ sendSpecialUp(view) }, 120L)
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
        var heldDpadX = 0
        var heldDpadY = 0
        val heldDpadKeys = linkedSetOf<Int>()
        val repeatHandler = android.os.Handler(mainLooper)
        var pendingRepress: Runnable? = null
        lateinit var repeatDirection: Runnable
        repeatDirection = Runnable {
            if (heldDpadKeys.isNotEmpty()) {
                heldDpadKeys.toList().forEach { view.sendKeyEvent(KeyEvent.ACTION_UP, it) }
                pendingRepress = Runnable {
                    if (heldDpadKeys.isNotEmpty()) {
                        heldDpadKeys.toList().forEach { view.sendKeyEvent(KeyEvent.ACTION_DOWN, it) }
                        repeatHandler.postDelayed(repeatDirection, 145L)
                    }
                }
                repeatHandler.postDelayed(pendingRepress!!, 35L)
            }
        }
        fun updateDirections(nextX: Int, nextY: Int) {
            val nextKeys = linkedSetOf<Int>()
            if (nextX < 0) nextKeys += KeyEvent.KEYCODE_DPAD_LEFT
            if (nextX > 0) nextKeys += KeyEvent.KEYCODE_DPAD_RIGHT
            if (nextY < 0) nextKeys += KeyEvent.KEYCODE_DPAD_UP
            if (nextY > 0) nextKeys += KeyEvent.KEYCODE_DPAD_DOWN
            val changed = nextKeys != heldDpadKeys
            (heldDpadKeys - nextKeys).forEach { view.sendKeyEvent(KeyEvent.ACTION_UP, it) }
            (nextKeys - heldDpadKeys).forEach { view.sendKeyEvent(KeyEvent.ACTION_DOWN, it) }
            heldDpadKeys.clear()
            heldDpadKeys.addAll(nextKeys)
            heldDpadX = nextX
            heldDpadY = nextY
            if (changed) {
                repeatHandler.removeCallbacks(repeatDirection)
                pendingRepress?.let(repeatHandler::removeCallbacks)
                pendingRepress = null
                if (heldDpadKeys.isNotEmpty()) repeatHandler.postDelayed(repeatDirection, 420L)
            }
        }
        val stick = VirtualJoystickView(this) { x, y ->
            // Character grids often advance on a fresh digital key press, not
            // on a continuously-held axis. Repeat a held touch direction like a key.
            fun axisDirection(value: Float, held: Int): Int {
                if (kotlin.math.abs(value) >= 0.35f) return if (value < 0f) -1 else 1
                if (held != 0 && kotlin.math.abs(value) >= 0.22f) return held
                return 0
            }
            val nextX = if (x == 0f && y == 0f) 0 else axisDirection(x, heldDpadX)
            val nextY = if (x == 0f && y == 0f) 0 else axisDirection(y, heldDpadY)
            updateDirections(nextX, nextY)
        }.apply { tag = "touch-controls"; alpha = 0.9f }
              val stickSize = (172 * resources.displayMetrics.density).toInt()
        val stickParams = FrameLayout.LayoutParams(stickSize, stickSize, Gravity.BOTTOM or Gravity.START)
        stickParams.leftMargin = (14 * resources.displayMetrics.density).toInt()
        stickParams.bottomMargin = (8 * resources.displayMetrics.density).toInt()
        root.addView(stick, stickParams)

        val dpadSize = dp(100)
        val dpad = DpadControlView(this, ::updateDirections).apply { tag = "touch-dpad"; alpha = 0.92f }
        val dpadParams = FrameLayout.LayoutParams(dpadSize, dpadSize, Gravity.BOTTOM or Gravity.START)
        dpadParams.leftMargin = dp(14 + 172 + 10)
        dpadParams.bottomMargin = dp(44)
        root.addView(dpad, dpadParams)

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(2, 2, 2, 2)
            alpha = 0.9f
            tag = "touch-buttons"
        }
        fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val upper = row().apply { tag = "button-row-top" }
        val lower = row()
        specialButtonLabels.forEachIndexed { index, label ->
            addTouchButton(if (index < 3) upper else lower, label, specialButtonKeys[index], view, compact = true)
        }
        right.addView(upper)
        right.addView(lower)
        val actionRow = row().apply { gravity = Gravity.CENTER }
        fun centerActionButton(label: String, keyCode: Int) = Button(this).apply {
            text = label
            textSize = 9f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = neonCircle(0xFF36E8FF.toInt())
            elevation = dp(7).toFloat()
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            contentDescription = label
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> view.sendKeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> view.sendKeyEvent(KeyEvent.ACTION_UP, keyCode)
                }
                true
            }
        }
        val select = centerActionButton("SELECT", KeyEvent.KEYCODE_BUTTON_SELECT)
        val start = centerActionButton("START", KeyEvent.KEYCODE_BUTTON_START)
        val actionSize = dp(50)
        val actionParams1 = LinearLayout.LayoutParams(actionSize, actionSize)
        actionParams1.setMargins(dp(10), 0, dp(10), 0)
        val actionParams2 = LinearLayout.LayoutParams(actionSize, actionSize)
        actionParams2.setMargins(dp(10), 0, dp(10), 0)
        actionRow.addView(select, actionParams1)
        actionRow.addView(start, actionParams2)
        val actionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            tag = "touch-actions"
            addView(actionRow)
        }
        val actionContainerParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        actionContainerParams.bottomMargin = dp(8)
        root.addView(actionContainer, actionContainerParams)
        val rightParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        rightParams.rightMargin = (8 * resources.displayMetrics.density).toInt()
        rightParams.bottomMargin = (8 * resources.displayMetrics.density).toInt()
        root.addView(right, rightParams)
        updateSpecialButton()
    }

    private class DpadControlView(context: android.content.Context, private val onDirections: (Int, Int) -> Unit) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val activePointers = mutableMapOf<Int, Pair<Int, Int>>()
        private var currentX = 0
        private var currentY = 0

        private fun directionAt(x: Float, y: Float): Pair<Int, Int> {
            val dx = x - width / 2f
            val dy = y - height / 2f
            val radius = minOf(width, height) * 0.48f
            if (kotlin.math.sqrt(dx * dx + dy * dy) < radius * 0.24f) return 0 to 0
            val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble()))
            return when {
                angle >= -22.5 && angle < 22.5 -> 1 to 0
                angle >= 22.5 && angle < 67.5 -> 1 to 1
                angle >= 67.5 && angle < 112.5 -> 0 to 1
                angle >= 112.5 && angle < 157.5 -> -1 to 1
                angle >= 157.5 || angle < -157.5 -> -1 to 0
                angle >= -157.5 && angle < -112.5 -> -1 to -1
                angle >= -112.5 && angle < -67.5 -> 0 to -1
                else -> 1 to -1
            }
        }

        private fun refreshDirections() {
            var x = 0
            var y = 0
            activePointers.values.forEach { direction ->
                if (direction.first != 0) x = direction.first
                if (direction.second != 0) y = direction.second
            }
            currentX = x
            currentY = y
            onDirections(x, y)
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) * 0.47f
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = 0xAA031923.toInt()
            canvas.drawCircle(cx, cy, radius, paint)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 2.5f * density
            paint.color = 0xFF36E8FF.toInt()
            paint.setShadowLayer(8f * density, 0f, 0f, 0xFF00DFFF.toInt())
            canvas.drawCircle(cx, cy, radius, paint)
            paint.clearShadowLayer()
            paint.style = android.graphics.Paint.Style.FILL
            val activeColor = 0xFF9AFFFF.toInt()
            val idleColor = 0xFF37CDE8.toInt()
            val arm = radius * 0.28f
            val half = radius * 0.23f
            paint.color = if (currentY < 0) activeColor else idleColor
            canvas.drawRoundRect(cx - half, cy - radius * 0.86f, cx + half, cy - radius * 0.20f, 8f * density, 8f * density, paint)
            paint.color = if (currentY > 0) activeColor else idleColor
            canvas.drawRoundRect(cx - half, cy + radius * 0.20f, cx + half, cy + radius * 0.86f, 8f * density, 8f * density, paint)
            paint.color = if (currentX < 0) activeColor else idleColor
            canvas.drawRoundRect(cx - radius * 0.86f, cy - half, cx - radius * 0.20f, cy + half, 8f * density, 8f * density, paint)
            paint.color = if (currentX > 0) activeColor else idleColor
            canvas.drawRoundRect(cx + radius * 0.20f, cy - half, cx + radius * 0.86f, cy + half, 8f * density, 8f * density, paint)
            paint.color = 0xFF08202A.toInt()
            canvas.drawCircle(cx, cy, radius * 0.20f, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val id = event.getPointerId(event.actionIndex)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> activePointers[id] = directionAt(event.getX(event.actionIndex), event.getY(event.actionIndex))
                MotionEvent.ACTION_MOVE -> for (index in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(index)
                    if (pointerId in activePointers) activePointers[pointerId] = directionAt(event.getX(index), event.getY(index))
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activePointers.remove(id)
            }
            refreshDirections()
            return true
        }
    }

    private class CrtGlassView(context: android.content.Context) : View(context) {
        private val scanline = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x1800EFFF
            strokeWidth = 1.2f * resources.displayMetrics.density
        }
        private val frame = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x5536E8FF
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        }

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val spacing = (4 * resources.displayMetrics.density).coerceAtLeast(3f)
            var y = 0f
            while (y < height) {
                canvas.drawLine(0f, y, width.toFloat(), y, scanline)
                y += spacing
            }
            val inset = frame.strokeWidth
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, 18f * resources.displayMetrics.density, 18f * resources.displayMetrics.density, frame)
        }
    }

    private class VirtualJoystickView(context: android.content.Context, private val onAxes: (Float, Float) -> Unit) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var axisX = 0f
        private var axisY = 0f
        private var activePointerId = MotionEvent.INVALID_POINTER_ID

        private fun update(x: Float, y: Float) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) * 0.37f
            var dx = (x - cx) / radius
            var dy = (y - cy) / radius
            val magnitude = kotlin.math.sqrt(dx * dx + dy * dy)
            if (magnitude > 1f) { dx /= magnitude; dy /= magnitude }
            axisX = dx
            axisY = dy
            onAxes(axisX, axisY)
            invalidate()
        }

        private fun release() {
            activePointerId = MotionEvent.INVALID_POINTER_ID
            axisX = 0f
            axisY = 0f
            onAxes(0f, 0f)
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) * 0.43f
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = 0xAA031923.toInt()
            canvas.drawCircle(cx, cy, radius, paint)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 3f * density
            paint.color = 0xFF36E8FF.toInt()
            paint.setShadowLayer(8f * density, 0f, 0f, 0xFF00DFFF.toInt())
            canvas.drawCircle(cx, cy, radius, paint)
            paint.color = 0xFF00DFFF.toInt()
            paint.clearShadowLayer()
            canvas.drawLine(cx - radius * 0.75f, cy, cx + radius * 0.75f, cy, paint)
            canvas.drawLine(cx, cy - radius * 0.75f, cx, cy + radius * 0.75f, paint)
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = 0xFFE0E5F0.toInt()
            canvas.drawCircle(cx + axisX * radius * 0.65f, cy + axisY * radius * 0.65f, radius * 0.31f, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = event.getPointerId(event.actionIndex)
                    update(event.getX(event.actionIndex), event.getY(event.actionIndex))
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(activePointerId)
                    if (index >= 0) update(event.getX(index), event.getY(index))
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == activePointerId) release()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
            }
            return true
        }
    }

    private fun addTouchButton(group: LinearLayout, label: String, keyCode: Int, view: GLRetroView, compact: Boolean = false) {
        val neon = when (label) {
            "A" -> 0xFFFF4FD8.toInt()
            "B" -> 0xFFFF4D6D.toInt()
            "C" -> 0xFFFFB84D.toInt()
            "X" -> 0xFF45F3FF.toInt()
            "Y" -> 0xFF69FF9A.toInt()
            "Z" -> 0xFF8C7BFF.toInt()
            else -> 0xFF36E8FF.toInt()
        }
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = 17f
            setTextColor(Color.WHITE)
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            background = neonCircle(neon)
            elevation = dp(7).toFloat()
            setShadowLayer(dp(7).toFloat(), 0f, 0f, neon)
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
        val buttonDp = if (compact) 46 else 58
        val size = (buttonDp * resources.displayMetrics.density).toInt()
        button.minWidth = size
        button.minHeight = size
        val params = LinearLayout.LayoutParams(size, size)
        val margin = (2 * resources.displayMetrics.density).toInt()
        params.setMargins(margin, margin, margin, margin)
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
            retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, dpadX, dpadY, 0)
            retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, if (kotlin.math.abs(leftX) > 0.2f) leftX else 0f, if (kotlin.math.abs(leftY) > 0.2f) leftY else 0f, 0)
            retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ), 0)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

}

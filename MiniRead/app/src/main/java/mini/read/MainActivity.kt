package mini.read

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Editable
import android.text.TextWatcher
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private enum class Screen { DIRECTORY, SETTINGS, READER, ACTIONS }

    private lateinit var root: SwipeFrameLayout
    private lateinit var prefs: SharedPreferences
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var screen = Screen.DIRECTORY
    private var darkMode = true
    private var activeFile: File? = null
    private var activeReader: FilePageReader? = null
    private var activeExternal = false
    private var externalSource: Uri? = null
    private var externalName = "document.txt"
    private var externalTemp: File? = null
    private var readerScroll: ScrollView? = null
    private var editorScroll: ScrollView? = null
    private var readerText: TextView? = null
    private var readerProgress: TextView? = null
    private var readerTitle: TextView? = null
    private var currentPageStart = 0L
    private var currentPageNext = 0L
    private var pageStarts = mutableListOf<Long>()
    private var editor: EditText? = null
    private var editorMode = false
    private var crownRemainder = 0f
    private var loading = false
    private var programmaticPageChange = false
    private var awaitingStorageSettings = false
    private var immersiveReader = false
    private var activityDownX = 0f
    private var activityDownY = 0f
    private var activityTrackingSwipe = false
    private var activitySwipeHandled = false
    private var activitySwipeEligible = false
    private var editorDraft = ""
    private var editorCursor = 0
    private var restoreSavedPosition = false

    private val storageRequestCode = 41
    private val importRequestCode = 42

    private val backgroundDark = Color.rgb(10, 13, 15)
    private val panelDark = Color.rgb(25, 31, 34)
    private val textDark = Color.rgb(235, 241, 240)
    private val mutedDark = Color.rgb(157, 174, 174)
    private val accent = Color.rgb(119, 224, 207)
    private val backgroundLight = Color.rgb(246, 248, 247)
    private val panelLight = Color.rgb(229, 237, 235)
    private val textLight = Color.rgb(22, 30, 31)
    private val mutedLight = Color.rgb(76, 96, 95)

    private val rootDirectory: File
        get() = File(Environment.getExternalStorageDirectory(), "MiniRead")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("miniread", MODE_PRIVATE)
        darkMode = prefs.getBoolean("dark_mode", true)
        applySystemTheme()
        root = SwipeFrameLayout(this) { direction ->
            if (direction > 0) navigateBack() else navigateForward()
        }
        root.setBackgroundColor(if (darkMode) backgroundDark else backgroundLight)
        setContentView(root)
        handleIntent(intent)
        ensureStorageAndShowDirectory()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) handleIntent(intent)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        activeReader?.close()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (awaitingStorageSettings) {
            awaitingStorageSettings = false
            if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
                rootDirectory.mkdirs()
                if (!activeExternal) showDirectory()
            } else if (!activeExternal) {
                showPermissionMessage()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && immersiveReader) setSystemBarsHidden(true)
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onBackPressed() {
        navigateBack()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activityDownX = event.x
                activityDownY = event.y
                activityTrackingSwipe = false
                activitySwipeHandled = false
                activitySwipeEligible = editorMode || !isTouchOnInteractiveView(root, event.rawX, event.rawY)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!activitySwipeHandled && activitySwipeEligible) {
                    val dx = event.x - activityDownX
                    val dy = event.y - activityDownY
                    if (abs(dx) > 36.dp() && abs(dx) > abs(dy) * 1.25f) {
                        activityTrackingSwipe = true
                        activitySwipeHandled = true
                        return true
                    }
                }
                if (activitySwipeHandled) return true
            }
            MotionEvent.ACTION_UP -> {
                if (activityTrackingSwipe && activitySwipeHandled) {
                    val dx = event.x - activityDownX
                    activityTrackingSwipe = false
                    activitySwipeHandled = false
                    activitySwipeEligible = false
                    if (abs(dx) > 56.dp()) routeHorizontalSwipe(if (dx > 0f) 1 else -1)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                val wasTracking = activitySwipeHandled
                activityTrackingSwipe = false
                activitySwipeHandled = false
                activitySwipeEligible = false
                if (wasTracking) return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isTouchOnInteractiveView(view: View, rawX: Float, rawY: Float): Boolean {
        if (view !== root && view !== readerText && (view.isClickable || view is SeekBar || view is EditText)) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            if (rawX >= location[0] && rawX < location[0] + view.width &&
                rawY >= location[1] && rawY < location[1] + view.height
            ) return true
        }
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                if (isTouchOnInteractiveView(view.getChildAt(index), rawX, rawY)) return true
            }
        }
        return false
    }

    private fun routeHorizontalSwipe(direction: Int) {
        if (editorMode) {
            if (direction < 0) showActions() else finishEditingToDirectory()
            return
        }
        if (direction > 0) navigateBack() else navigateForward()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            var delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (delta == 0f) delta = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (delta == 0f) delta = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (delta != 0f) {
                val sensitivity = prefs.getInt("crown_sensitivity", 4).coerceIn(1, 8).toFloat()
                crownRemainder += delta
                val pixels = (crownRemainder * sensitivity).roundToInt()
                if (pixels != 0) {
                    crownRemainder -= pixels / sensitivity
                    val target: View? = when (screen) {
                        Screen.READER -> if (editorMode) editorScroll else readerScroll
                        else -> findScrollView(root)
                    }
                    target?.scrollBy(0, -pixels)
                }
                // Consume REL_WHEEL even when the page cannot scroll. This avoids the
                // incompatible OPPO RSB/WearVision dispatch path described in the device notes.
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == storageRequestCode) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                rootDirectory.mkdirs()
                showDirectory()
            } else {
                showPermissionMessage()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == importRequestCode && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
                // A one-shot grant is sufficient for this copy operation.
            }
            copyUriToLibrary(uri)
        }
    }

    private fun handleIntent(incoming: Intent) {
        if (incoming.action == Intent.ACTION_VIEW && incoming.data != null) {
            activeExternal = true
            externalSource = incoming.data
            externalName = queryDisplayName(incoming.data!!) ?: "document.txt"
            openExternalDocument(incoming.data!!)
        }
    }

    private fun ensureStorageAndShowDirectory() {
        if (activeExternal) return
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            awaitingStorageSettings = true
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                awaitingStorageSettings = false
                showPermissionMessage()
            }
            return
        }
        if (Build.VERSION.SDK_INT <= 28) {
            val missing = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), storageRequestCode)
                return
            }
        }
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    showPermissionMessage()
                }
            } else {
                showPermissionMessage()
            }
        }
        if (activeExternal && externalTemp == null) return
        showDirectory()
    }

    private fun showPermissionMessage() {
        root.removeAllViews()
        val column = pageColumn()
        addHeader(column, getString(R.string.app_name), getString(R.string.app_subtitle))
        addBodyText(column, getString(R.string.permission_needed), true)
        val open = actionButton("${getString(R.string.settings)}  ›")
        open.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 30) {
                awaitingStorageSettings = true
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    awaitingStorageSettings = false
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        column.addView(open)
        mountPage(column)
    }

    private fun showDirectory() {
        screen = Screen.DIRECTORY
        immersiveReader = false
        setSystemBarsHidden(false)
        editorMode = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root.removeAllViews()
        val page = pageColumn()
        val toolbar = LinearLayout(this)
        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        val title = label(getString(R.string.app_name), 17f, true)
        toolbar.addView(title, LinearLayout.LayoutParams(0, 38.dp(), 1f))
        val settings = actionButton("⚙")
        settings.contentDescription = getString(R.string.settings)
        settings.minHeight = 38.dp()
        settings.minWidth = 38.dp()
        settings.setOnClickListener { showSettings() }
        toolbar.addView(settings, LinearLayout.LayoutParams(40.dp(), 38.dp()))
        page.addView(toolbar)

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(0, 2.dp(), 0, 4.dp())
        val files = libraryFiles()
        if (files.isEmpty()) {
            val empty = addBodyText(list, getString(R.string.no_documents), false)
            empty.setPadding(12.dp(), 28.dp(), 12.dp(), 28.dp())
        } else {
            files.forEach { file ->
                val row = documentRow(file)
                list.addView(row, LinearLayout.LayoutParams(-1, 52.dp()).apply { bottomMargin = 4.dp() })
            }
        }
        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        mountPage(page)
    }

    private fun showSettings() {
        screen = Screen.SETTINGS
        immersiveReader = false
        setSystemBarsHidden(false)
        root.removeAllViews()
        val page = pageColumn()
        page.addView(label(getString(R.string.settings), 17f, true), LinearLayout.LayoutParams(-1, 22.dp()))

        val import = actionButton(getString(R.string.import_document))
        import.setOnClickListener { launchImporter() }
        page.addView(import, LinearLayout.LayoutParams(-1, 36.dp()).apply { bottomMargin = 3.dp() })
        val newFile = actionButton(getString(R.string.new_document))
        newFile.setOnClickListener { createNewDocument() }
        page.addView(newFile, LinearLayout.LayoutParams(-1, 36.dp()).apply { bottomMargin = 5.dp() })

        val fontTitle = label(getString(R.string.font_label), 15f, true)
        page.addView(fontTitle, LinearLayout.LayoutParams(-1, 20.dp()))
        val fontRow = LinearLayout(this)
        fontRow.gravity = Gravity.CENTER_VERTICAL
        val seek = SeekBar(this)
        seek.max = 12
        seek.progress = prefs.getInt("font_size", 4).coerceIn(0, 12)
        fontRow.addView(seek, LinearLayout.LayoutParams(0, 36.dp(), 1f))
        val sizeValue = label(fontSizeSp().toInt().toString(), 13f, false)
        fontRow.addView(sizeValue, LinearLayout.LayoutParams(34.dp(), 36.dp()))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                prefs.edit().putInt("font_size", value).apply()
                sizeValue.text = fontSizeSp().toInt().toString()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        page.addView(fontRow, LinearLayout.LayoutParams(-1, 32.dp()).apply { bottomMargin = 2.dp() })

        val crownTitle = label(getString(R.string.crown_sensitivity), 15f, true)
        page.addView(crownTitle, LinearLayout.LayoutParams(-1, 20.dp()))
        val crownRow = LinearLayout(this)
        crownRow.gravity = Gravity.CENTER_VERTICAL
        val crownSeek = SeekBar(this)
        crownSeek.max = 7
        val crownValue = label(prefs.getInt("crown_sensitivity", 4).coerceIn(1, 8).toString(), 13f, false)
        crownSeek.progress = prefs.getInt("crown_sensitivity", 4).coerceIn(1, 8) - 1
        crownSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                val sensitivity = (value + 1).coerceIn(1, 8)
                prefs.edit().putInt("crown_sensitivity", sensitivity).apply()
                crownValue.text = sensitivity.toString()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        crownRow.addView(crownSeek, LinearLayout.LayoutParams(0, 32.dp(), 1f))
        crownRow.addView(crownValue, LinearLayout.LayoutParams(34.dp(), 32.dp()))
        page.addView(crownRow, LinearLayout.LayoutParams(-1, 32.dp()).apply { bottomMargin = 2.dp() })

        val themeTitle = label("${getString(R.string.dark_mode)} / ${getString(R.string.light_mode)}", 14f, true)
        page.addView(themeTitle, LinearLayout.LayoutParams(-1, 18.dp()))
        val themeRow = LinearLayout(this)
        themeRow.orientation = LinearLayout.HORIZONTAL
        val dark = actionButton(getString(R.string.dark_mode))
        val light = actionButton(getString(R.string.light_mode))
        dark.setOnClickListener { setDarkMode(true) }
        light.setOnClickListener { setDarkMode(false) }
        themeRow.addView(dark, LinearLayout.LayoutParams(0, 36.dp(), 1f).apply { rightMargin = 6.dp() })
        themeRow.addView(light, LinearLayout.LayoutParams(0, 36.dp(), 1f))
        page.addView(themeRow)
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.setBackgroundColor(if (darkMode) backgroundDark else backgroundLight)
        val wrapper = LinearLayout(this)
        wrapper.orientation = LinearLayout.VERTICAL
        wrapper.setBackgroundColor(if (darkMode) backgroundDark else backgroundLight)
        wrapper.minimumHeight = resources.displayMetrics.heightPixels
        wrapper.addView(page)
        scroll.addView(wrapper)
        mountPage(scroll)
    }

    private fun setDarkMode(value: Boolean) {
        darkMode = value
        prefs.edit().putBoolean("dark_mode", value).apply()
        root.setBackgroundColor(if (value) backgroundDark else backgroundLight)
        applySystemTheme()
        when (screen) {
            Screen.DIRECTORY -> showDirectory()
            Screen.SETTINGS -> showSettings()
            Screen.READER -> showReader()
            Screen.ACTIONS -> showActions()
        }
    }

    private fun showReader() {
        if (editorMode) {
            val value = editor?.text?.toString() ?: editorDraft
            val cursor = editor?.selectionStart?.coerceAtLeast(0) ?: editorCursor
            showEditor(value, cursor)
            return
        }
        screen = Screen.READER
        immersiveReader = true
        setSystemBarsHidden(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.setBackgroundColor(if (darkMode) backgroundDark else backgroundLight)
        scroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (programmaticPageChange) return@setOnScrollChangeListener
            val child = scroll.getChildAt(0)
            if (child != null && scrollY > oldScrollY && scrollY >= child.height - scroll.height - 40.dp()) {
                loadNextPageIfNeeded()
            }
            if (scrollY <= 8.dp() && oldScrollY > scrollY) loadPreviousPageIfNeeded()
            saveProgress()
        }
        scroll.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) saveProgress()
            false
        }
        readerScroll = scroll
        readerTitle = null
        readerProgress = null
        readerText = TextView(this)
        readerText?.setTextColor(if (darkMode) textDark else textLight)
        readerText?.setTextSize(fontSizeSp())
        readerText?.setLineSpacing(2f, 1.18f)
        readerText?.setPadding(8.dp(), 6.dp(), 8.dp(), 8.dp())
        readerText?.setTextIsSelectable(true)
        readerText?.setTypeface(Typeface.create("sans", Typeface.NORMAL))
        readerText?.setLinkTextColor(if (darkMode) accent else Color.rgb(0, 111, 97))
        readerText?.movementMethod = LinkMovementMethod.getInstance()
        scroll.addView(readerText, ViewGroup.LayoutParams(-1, -2))
        mountPage(scroll)
        renderPage(currentPageStart, false)
    }

    private fun showActions() {
        if (editorMode) {
            editor?.let {
                editorDraft = it.text?.toString().orEmpty()
                editorCursor = it.selectionStart.coerceAtLeast(0)
            }
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
        }
        screen = Screen.ACTIONS
        immersiveReader = false
        setSystemBarsHidden(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root.removeAllViews()
        val page = pageColumn()
        page.addView(label(activeFile?.let { displayTitle(it) } ?: externalName, 16f, true), LinearLayout.LayoutParams(-1, 28.dp()))
        if (activeExternal) {
            val import = actionButton(getString(R.string.import_to_notes))
            import.setOnClickListener { importExternalToLibrary() }
            page.addView(import, LinearLayout.LayoutParams(-1, 44.dp()).apply { bottomMargin = 4.dp() })
        } else if (editorMode) {
            val exit = actionButton(getString(R.string.exit_edit))
            exit.setOnClickListener { finishEditing() }
            page.addView(exit, LinearLayout.LayoutParams(-1, 44.dp()).apply { bottomMargin = 4.dp() })
            val newline = actionButton(getString(R.string.insert_newline))
            newline.setOnClickListener { insertNewline() }
            page.addView(newline, LinearLayout.LayoutParams(-1, 44.dp()).apply { bottomMargin = 4.dp() })
        } else {
            val rename = actionButton(getString(R.string.rename))
            rename.setOnClickListener { showRenameDialog() }
            page.addView(rename, LinearLayout.LayoutParams(-1, 44.dp()).apply { bottomMargin = 4.dp() })
            val edit = actionButton(getString(R.string.edit))
            edit.setOnClickListener { beginEditing() }
            page.addView(edit, LinearLayout.LayoutParams(-1, 44.dp()).apply { bottomMargin = 4.dp() })
            val delete = actionButton(getString(R.string.delete))
            delete.setOnClickListener { confirmDelete() }
            page.addView(delete, LinearLayout.LayoutParams(-1, 44.dp()).apply { bottomMargin = 4.dp() })
        }
        mountPage(page)
    }

    private fun beginEditing() {
        val file = activeFile ?: return
        if (file.length() > 4_000_000L) {
            toast(getString(R.string.edit_too_large))
            return
        }
        executor.execute {
            val content = try {
                FileInputStream(file).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: Exception) {
                ""
            }
            mainHandler.post {
                editorMode = true
                showEditor(content)
            }
        }
    }

    private fun showEditor(content: String, cursorPosition: Int = content.length) {
        screen = Screen.READER
        immersiveReader = true
        setSystemBarsHidden(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        editorDraft = content
        editorCursor = cursorPosition.coerceIn(0, content.length)
        val field = SwipeEditText(this) { direction ->
            if (direction < 0) showActions() else finishEditingToDirectory()
        }
        field.setText(content)
        field.setTextColor(if (darkMode) textDark else textLight)
        field.setHintTextColor(if (darkMode) mutedDark else mutedLight)
        field.setTextSize(fontSizeSp())
        field.setLineSpacing(2f, 1.15f)
        field.gravity = Gravity.TOP or Gravity.START
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        field.showSoftInputOnFocus = true
        field.setPadding(8.dp(), 6.dp(), 8.dp(), 8.dp())
        field.background = null
        field.minHeight = resources.displayMetrics.heightPixels
        editor = field
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                editorDraft = s?.toString().orEmpty()
            }
            override fun afterTextChanged(s: Editable?) {
                editorCursor = field.selectionStart.coerceAtLeast(0)
            }
        })
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.setBackgroundColor(if (darkMode) backgroundDark else backgroundLight)
        scroll.addView(field, ViewGroup.LayoutParams(-1, -2))
        editorScroll = scroll
        mountPage(scroll)
        field.requestFocus()
        field.setSelection(cursorPosition.coerceIn(0, field.length()))
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun finishEditing() = saveEditor(returnToDirectory = false)

    private fun finishEditingToDirectory() = saveEditor(returnToDirectory = true)

    private fun saveEditor(returnToDirectory: Boolean) {
        val file = activeFile ?: return
        val value = editor?.text?.toString() ?: editorDraft
        executor.execute {
            try {
                FileOutputStream(file, false).bufferedWriter(Charsets.UTF_8).use { it.write(value) }
            } catch (_: Exception) {
                mainHandler.post { toast(getString(R.string.storage_error)) }
                return@execute
            }
            mainHandler.post {
                editorMode = false
                editor = null
                editorScroll = null
                editorDraft = ""
                editorCursor = 0
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
                if (returnToDirectory) {
                    activeFile = null
                    showDirectory()
                } else {
                    currentPageStart = 0L
                    pageStarts.clear()
                    showReader()
                }
            }
        }
    }

    private fun insertNewline() {
        val target = editor ?: return
        val start = target.selectionStart.coerceAtLeast(0)
        target.text?.insert(start, "\n")
        val cursor = (start + 1).coerceAtMost(target.length())
        showEditor(target.text?.toString().orEmpty(), cursor)
    }

    private fun renderPage(start: Long, keepScroll: Boolean) {
        val file = activeFile ?: return
        if (loading) return
        loading = true
        executor.execute {
            val page = try { activeReader?.readPage(start) } catch (_: Exception) { null }
            mainHandler.post {
                loading = false
                if (page == null) {
                    toast(getString(R.string.storage_error))
                    return@post
                }
                currentPageStart = page.start
                currentPageNext = page.nextStart
                if (!pageStarts.contains(page.start)) pageStarts.add(page.start)
                val styled = if (file.extension.lowercase(Locale.US) == "md") markdownStyle(page.text) else SpannableStringBuilder(page.text)
                if (!keepScroll) programmaticPageChange = true
                readerText?.animate()?.cancel()
                readerText?.alpha = 0.76f
                readerText?.text = styled
                readerText?.animate()?.alpha(1f)?.setDuration(140L)?.setInterpolator(android.view.animation.DecelerateInterpolator())?.start()
                readerText?.setTextSize(fontSizeSp())
                val restore = restoreSavedPosition
                restoreSavedPosition = false
                if (!restore) prefs.edit().putFloat(scrollRatioKey(file), 0f).apply()
                updateProgress(file, page.start)
                if (!keepScroll) {
                    readerScroll?.post {
                        val scrollView = readerScroll
                        if (scrollView != null && restore) {
                            val range = (scrollView.getChildAt(0)?.height ?: 0) - scrollView.height
                            val ratio = prefs.getFloat(scrollRatioKey(file), 0f).coerceIn(0f, 1f)
                            scrollView.scrollTo(0, (range.coerceAtLeast(0) * ratio).roundToInt())
                        } else {
                            scrollView?.scrollTo(0, 0)
                        }
                        programmaticPageChange = false
                    } ?: run { programmaticPageChange = false }
                }
            }
        }
    }

    private fun loadNextPageIfNeeded() {
        val reader = activeReader ?: return
        if (currentPageNext >= reader.length()) return
        renderPage(currentPageNext, false)
    }

    private fun loadPreviousPageIfNeeded() {
        if (currentPageStart <= 0L || loading) return
        val index = pageStarts.indexOf(currentPageStart)
        if (index > 0) renderPage(pageStarts[index - 1], false)
    }

    private fun updateProgress(file: File, start: Long) {
        val percent = if (file.length() == 0L) 100 else ((start * 100L) / file.length()).toInt().coerceIn(0, 100)
        readerProgress?.text = getString(R.string.page_indicator, percent)
        prefs.edit().putLong(progressKey(file), start).apply()
    }

    private fun saveProgress() {
        val file = activeFile ?: return
        if (screen == Screen.READER && !editorMode) {
            val scroll = readerScroll
            val child = scroll?.getChildAt(0)
            val range = (child?.height ?: 0) - (scroll?.height ?: 0)
            val ratio = if (range > 0) (scroll!!.scrollY.toFloat() / range).coerceIn(0f, 1f) else 0f
            prefs.edit()
                .putLong(progressKey(file), currentPageStart)
                .putFloat(scrollRatioKey(file), ratio)
                .apply()
        }
    }

    private fun showRenameDialog() {
        val file = activeFile ?: return
        val input = EditText(this)
        input.setSingleLine(true)
        input.setText(file.nameWithoutExtension)
        input.setSelection(input.length())
        input.hint = getString(R.string.rename_hint)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename))
            .setView(input)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.save), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val clean = input.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (clean.isNotEmpty()) {
                    val ext = file.extension.ifEmpty { "txt" }
                    val target = uniqueFile(clean + "." + ext)
                    if (file.renameTo(target)) {
                        activeFile = target
                        dialog.dismiss()
                        showActions()
                    } else toast(getString(R.string.storage_error))
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete() {
        val file = activeFile ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_confirm))
            .setMessage(file.name)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                if (!file.delete()) toast(getString(R.string.storage_error)) else {
                    prefs.edit().remove(progressKey(file)).apply()
                    activeFile = null
                    showDirectory()
                }
            }.show()
    }

    private fun launchImporter() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/markdown"))
        startActivityForResult(intent, importRequestCode)
    }

    private fun createNewDocument() {
        val file = uniqueFile("新建文本文档.txt")
        try {
            file.writeText("", Charsets.UTF_8)
            activeFile = file
            activeReader?.close()
            activeReader = FilePageReader(file)
            currentPageStart = 0L
            pageStarts.clear()
            editorMode = true
            showEditor("")
        } catch (_: Exception) {
            toast(getString(R.string.storage_error))
        }
    }

    private fun copyUriToLibrary(uri: Uri) {
        executor.execute {
            val name = queryDisplayName(uri) ?: "document.txt"
            if (!name.lowercase(Locale.US).endsWith(".md") && !name.lowercase(Locale.US).endsWith(".txt")) {
                mainHandler.post {
                    toast(getString(R.string.file_type_error))
                    showSettings()
                }
                return@execute
            }
            val target = uniqueFile(name)
            val ok = try {
                contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(target).use { output -> input.copyTo(output, 32 * 1024) } }
                target.exists()
            } catch (_: Exception) { false }
            mainHandler.post {
                toast(if (ok) getString(R.string.import_success) else getString(R.string.storage_error))
                showDirectory()
            }
        }
    }

    private fun openExternalDocument(uri: Uri) {
        root.removeAllViews()
        val page = pageColumn()
        addHeader(page, getString(R.string.reading), externalName)
        addBodyText(page, getString(R.string.import_to_notes), false)
        mountPage(page)
        executor.execute {
            val temp = File(cacheDir, "opened_${System.currentTimeMillis()}.txt")
            val ok = try {
                contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(temp).use { output -> input.copyTo(output, 32 * 1024) } }
                temp.exists()
            } catch (_: Exception) { false }
            mainHandler.post {
                if (!ok) {
                    toast(getString(R.string.storage_error))
                    activeExternal = false
                    showDirectory()
                } else {
                    externalTemp = temp
                    activeFile = temp
                    activeReader = FilePageReader(temp)
                    currentPageStart = 0L
                    pageStarts.clear()
                    showReader()
                }
            }
        }
    }

    private fun importExternalToLibrary() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            toast(getString(R.string.permission_needed))
            awaitingStorageSettings = true
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                awaitingStorageSettings = false
                showPermissionMessage()
            }
            return
        }
        val source = externalSource ?: return
        executor.execute {
            val name = queryDisplayName(source) ?: externalName
            if (!name.lowercase(Locale.US).endsWith(".txt") && !name.lowercase(Locale.US).endsWith(".md")) {
                mainHandler.post { toast(getString(R.string.file_type_error)) }
                return@execute
            }
            val target = uniqueFile(name)
            val copied = try {
                contentResolver.openInputStream(source)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output, 32 * 1024) }
                }
                target.exists()
            } catch (_: Exception) { false }
            if (!copied) {
                mainHandler.post { toast(getString(R.string.storage_error)) }
                return@execute
            }
            try { contentResolver.delete(source, null, null) } catch (_: Exception) { }
            mainHandler.post {
                toast(getString(R.string.import_success))
                activeExternal = false
                activeFile = null
                finish()
            }
        }
    }

    private fun navigateBack() {
        when (screen) {
            Screen.DIRECTORY -> finish()
            Screen.SETTINGS -> showDirectory()
            Screen.READER -> {
                if (editorMode) showActions() else {
                    saveProgress()
                    if (activeExternal) finish() else showDirectory()
                }
            }
            Screen.ACTIONS -> showReader()
        }
    }

    private fun navigateForward() {
        when (screen) {
            Screen.DIRECTORY -> showSettings()
            Screen.READER -> showActions()
            Screen.SETTINGS, Screen.ACTIONS -> Unit
        }
    }

    private fun libraryFiles(): List<File> {
        return rootDirectory.listFiles { file ->
            file.isFile && (file.extension.equals("txt", true) || file.extension.equals("md", true))
        }?.sortedBy { it.name.lowercase(Locale.getDefault()) } ?: emptyList()
    }

    private fun documentRow(file: File): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(10.dp(), 2.dp(), 8.dp(), 2.dp())
        stylePanel(row)
        val title = label(file.nameWithoutExtension, 14f, true)
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        row.addView(title, LinearLayout.LayoutParams(-1, 25.dp()))
        val meta = label(".${file.extension.lowercase(Locale.US)}  ·  ${formatBytes(file.length())}", 10f, false)
        row.addView(meta, LinearLayout.LayoutParams(-1, 18.dp()))
        row.setOnClickListener {
            activeExternal = false
            activeFile = file
            activeReader?.close()
            activeReader = FilePageReader(file)
            currentPageStart = prefs.getLong(progressKey(file), 0L).coerceIn(0L, file.length())
            restoreSavedPosition = true
            pageStarts.clear()
            showReader()
        }
        row.setOnLongClickListener {
            activeFile = file
            showActions()
            true
        }
        return row
    }

    private fun pageColumn(): LinearLayout {
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
        column.setBackgroundColor(if (darkMode) backgroundDark else backgroundLight)
        return column
    }

    private fun addHeader(parent: LinearLayout, title: String, subtitle: String) {
        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        val icon = ImageView(this)
        icon.setImageResource(mini.read.R.drawable.icon)
        icon.scaleType = ImageView.ScaleType.CENTER_CROP
        header.addView(icon, LinearLayout.LayoutParams(30.dp(), 30.dp()).apply { rightMargin = 7.dp() })
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.gravity = Gravity.CENTER_VERTICAL
        texts.addView(label(title, 18f, true), LinearLayout.LayoutParams(-1, 22.dp()))
        texts.addView(label(subtitle, 9f, false), LinearLayout.LayoutParams(-1, 13.dp()))
        header.addView(texts, LinearLayout.LayoutParams(0, 36.dp(), 1f))
        parent.addView(header, LinearLayout.LayoutParams(-1, 38.dp()).apply { bottomMargin = 3.dp() })
    }

    private fun addBodyText(parent: ViewGroup, text: String, emphasis: Boolean): TextView {
        val value = label(text, if (emphasis) 16f else 14f, emphasis)
        value.setPadding(4.dp(), 14.dp(), 4.dp(), 14.dp())
        parent.addView(value, ViewGroup.LayoutParams(-1, -2))
        return value
    }

    private fun label(text: String, size: Float, bold: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(size)
            setTextColor(if (darkMode) textDark else textLight)
            typeface = Typeface.create("sans", if (bold) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    private fun actionButton(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(14f)
            setTextColor(if (darkMode) textDark else textLight)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            stylePanel(this)
            setPadding(12.dp(), 4.dp(), 12.dp(), 4.dp())
        }
    }

    private fun stylePanel(view: View) {
        val drawable = android.graphics.drawable.GradientDrawable()
        drawable.cornerRadius = 6.dp().toFloat()
        drawable.setColor(if (darkMode) panelDark else panelLight)
        if (Build.VERSION.SDK_INT >= 21) {
            val mask = android.graphics.drawable.GradientDrawable()
            mask.cornerRadius = 6.dp().toFloat()
            mask.setColor(Color.WHITE)
            view.background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(Color.argb(44, Color.red(accent), Color.green(accent), Color.blue(accent))),
                drawable,
                mask
            )
        } else {
            view.background = drawable
        }
    }

    private fun markdownStyle(text: String): SpannableStringBuilder {
        val output = SpannableStringBuilder()
        var inCodeBlock = false
        val headingColor = if (darkMode) accent else Color.rgb(0, 111, 97)
        val codeBackground = if (darkMode) Color.rgb(34, 45, 47) else Color.rgb(220, 230, 228)
        val lines = text.split('\n')

        lines.forEachIndexed { index, rawLine ->
            var line = rawLine.trimEnd('\r')
            if (line.trimStart().startsWith("```")) {
                inCodeBlock = !inCodeBlock
                if (index < lines.lastIndex) output.append('\n')
                return@forEachIndexed
            }

            var headingLevel = 0
            var quote = false
            when {
                inCodeBlock -> Unit
                line.matches(Regex("\\s*(---+|\\*\\*\\*+|___+)\\s*")) -> line = ""
                line.trimStart().startsWith(">") -> {
                    quote = true
                    line = line.trimStart().removePrefix(">").removePrefix(" ")
                }
                Regex("^\\s*#{1,6}\\s+").containsMatchIn(line) -> {
                    val prefix = Regex("^\\s*#{1,6}\\s+").find(line)!!
                    headingLevel = prefix.value.count { it == '#' }
                    line = line.removeRange(prefix.range)
                }
                Regex("^\\s*[-*+]\\s+\\[[ xX]]\\s+").containsMatchIn(line) -> {
                    val prefix = Regex("^\\s*[-*+]\\s+\\[[ xX]]\\s+").find(line)!!
                    val checked = Regex("\\[[xX]]").containsMatchIn(prefix.value)
                    line = (if (checked) "✓  " else "□  ") + line.removeRange(prefix.range)
                }
                Regex("^\\s*[-*+]\\s+").containsMatchIn(line) -> {
                    val prefix = Regex("^\\s*[-*+]\\s+").find(line)!!
                    line = "•  " + line.removeRange(prefix.range)
                }
                Regex("^\\s*\\d+[.)]\\s+").containsMatchIn(line) -> {
                    val prefix = Regex("^\\s*(\\d+[.)])\\s+").find(line)!!
                    line = prefix.groupValues[1] + "  " + line.removeRange(prefix.range)
                }
                line.trimStart().startsWith("|") && line.contains("|") -> {
                    val cells = line.trim().trim('|').split('|').map { it.trim() }
                    if (cells.isNotEmpty() && cells.all { it.isEmpty() || it.matches(Regex(":?-{2,}:?")) }) {
                        line = ""
                    } else {
                        line = cells.filter { it.isNotEmpty() }.joinToString("     ·     ")
                    }
                }
            }

            val rendered = SpannableStringBuilder(line)
            if (inCodeBlock) {
                if (rendered.isNotEmpty()) {
                    rendered.setSpan(TypefaceSpan("monospace"), 0, rendered.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    rendered.setSpan(BackgroundColorSpan(codeBackground), 0, rendered.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else {
                renderInlineMarkdown(rendered, headingColor, codeBackground)
                if (headingLevel > 0 && rendered.isNotEmpty()) {
                    rendered.setSpan(StyleSpan(Typeface.BOLD), 0, rendered.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    rendered.setSpan(RelativeSizeSpan(if (headingLevel <= 2) 1.14f else 1.07f), 0, rendered.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    rendered.setSpan(ForegroundColorSpan(headingColor), 0, rendered.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (quote && rendered.isNotEmpty()) {
                    rendered.setSpan(ForegroundColorSpan(headingColor), 0, rendered.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    rendered.setSpan(BackgroundColorSpan(codeBackground), 0, rendered.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            output.append(rendered)
            if (index < lines.lastIndex) output.append('\n')
        }
        return output
    }

    private fun renderInlineMarkdown(text: SpannableStringBuilder, linkColor: Int, codeBackground: Int) {
        val linkPattern = Regex("\\[([^]]+)]\\((https?://[^)]+)\\)")
        linkPattern.findAll(text.toString()).toList().asReversed().forEach { match ->
            val label = match.groups[1] ?: return@forEach
            val url = match.groups[2]?.value ?: return@forEach
            val start = label.range.first
            val end = label.range.last + 1
            text.setSpan(URLSpan(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(linkColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.delete(end, match.range.last + 1)
            text.delete(match.range.first, start)
        }

        fun replaceMarkers(pattern: Regex, style: () -> Any, markerSize: Int = 1, background: Int? = null) {
            pattern.findAll(text.toString()).toList().asReversed().forEach { match ->
                val content = match.groups[1] ?: return@forEach
                val start = content.range.first
                val end = content.range.last + 1
                text.setSpan(style(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (background != null) text.setSpan(BackgroundColorSpan(background), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.delete(end, match.range.last + 1)
                text.delete(match.range.first, match.range.first + markerSize)
            }
        }

        replaceMarkers(Regex("\\*\\*(.+?)\\*\\*"), { StyleSpan(Typeface.BOLD) }, 2)
        replaceMarkers(Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)"), { StyleSpan(Typeface.ITALIC) })
        replaceMarkers(Regex("_([^_]+)_"), { StyleSpan(Typeface.ITALIC) })
        replaceMarkers(Regex("~~(.+?)~~"), { StrikethroughSpan() }, 2)
        replaceMarkers(Regex("`([^`]+)`"), { TypefaceSpan("monospace") }, 1, codeBackground)
    }

    private fun mountPage(view: View) {
        root.removeAllViews()
        view.setBackgroundColor(if (darkMode) backgroundDark else backgroundLight)
        view.animate().cancel()
        view.alpha = 0.92f
        view.translationX = 8.dp().toFloat()
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(190L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.7f))
            .start()
    }

    private fun setSystemBarsHidden(hidden: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(!hidden)
            val controller = window.insetsController ?: return
            if (hidden) {
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            } else {
                controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (hidden) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    private fun applySystemTheme() {
        window.statusBarColor = if (darkMode) backgroundDark else backgroundLight
        window.navigationBarColor = if (darkMode) Color.BLACK else backgroundLight
        if (Build.VERSION.SDK_INT < 30) {
            window.decorView.systemUiVisibility = if (darkMode) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else if (immersiveReader) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    private fun findScrollView(view: View): ScrollView? {
        if (view is ScrollView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findScrollView(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) { null }
    }

    private fun uniqueFile(name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "txt")
        var candidate = File(rootDirectory, name)
        var index = 2
        while (candidate.exists()) {
            candidate = File(rootDirectory, "$base ($index).$ext")
            index++
        }
        return candidate
    }

    private fun displayTitle(file: File): String = file.nameWithoutExtension.ifEmpty { file.name }

    private fun progressKey(file: File): String = "progress:" + file.absolutePath
    private fun scrollRatioKey(file: File): String = "scroll-ratio:" + file.absolutePath

    private fun fontSizeSp(): Float = 13f + prefs.getInt("font_size", 4).coerceIn(0, 12) * 0.75f

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
            bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
            else -> "$bytes B"
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()
    private fun Float.dp(): Int = (this * resources.displayMetrics.density).roundToInt()

    private class SwipeEditText(
        context: Context,
        private val onSwipe: (Int) -> Unit
    ) : EditText(context) {
        private var downX = 0f
        private var downY = 0f
        private var trackingSwipe = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    trackingSwipe = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!trackingSwipe && abs(dx) > 32.dp() && abs(dx) > abs(dy) * 1.2f) {
                        trackingSwipe = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    if (trackingSwipe) return true
                }
                MotionEvent.ACTION_UP -> {
                    if (trackingSwipe) {
                        val dx = event.x - downX
                        trackingSwipe = false
                        onSwipe(if (dx > 0f) 1 else -1)
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    trackingSwipe = false
                }
            }
            return super.onTouchEvent(event)
        }

        private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()
    }

    private class SwipeFrameLayout(context: Context, private val callback: (Int) -> Unit) : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var tracking = false

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            return super.onTouchEvent(event)
        }

        private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()
    }
}

private data class Page(val start: Long, val nextStart: Long, val text: String)

private class FilePageReader(private val file: File) {
    companion object { private const val WINDOW_BYTES = 12 * 1024 }
    fun length(): Long = file.length()
    fun close() = Unit

    fun readPage(startOffset: Long): Page {
        val start = startOffset.coerceIn(0L, file.length())
        if (file.length() == 0L || start >= file.length()) return Page(start, file.length(), "")
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val available = min(WINDOW_BYTES.toLong(), file.length() - start).toInt()
            val bytes = ByteArray(available)
            val count = raf.read(bytes)
            if (count <= 0) return Page(start, file.length(), "")
            var end = count
            if (start + count < file.length()) {
                var newline = count - 1
                while (newline > count / 2 && bytes[newline].toInt() != 10) newline--
                if (newline > 0) end = newline + 1
            }
            val text = String(bytes, 0, end, StandardCharsets.UTF_8)
            return Page(start, (start + end).coerceAtMost(file.length()), text)
        }
    }
}

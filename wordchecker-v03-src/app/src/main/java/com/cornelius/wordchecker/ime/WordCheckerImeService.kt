package com.cornelius.wordchecker.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cornelius.wordchecker.Prefs
import com.cornelius.wordchecker.core.CorrectionDecision
import com.cornelius.wordchecker.core.FieldContext
import com.cornelius.wordchecker.core.SafeDeleteEngine
import com.cornelius.wordchecker.core.SpellTarget
import com.cornelius.wordchecker.core.SpellTextEngine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class WordCheckerImeService : InputMethodService(), SpellCheckerSession.SpellCheckerSessionListener {
    private val engine = SafeDeleteEngine()
    private val spellText = SpellTextEngine()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val deleteHandler = Handler(Looper.getMainLooper())
    private val spellHandler = Handler(Looper.getMainLooper())

    private var activeEditorInfo: EditorInfo? = null
    private var shift = false
    private var symbolMode = false
    private var emojiMode = false

    private var statusView: TextView? = null
    private var undoButton: Button? = null
    private var keyboardRoot: LinearLayout? = null
    private var mainPanel: LinearLayout? = null
    private var emojiPanel: LinearLayout? = null
    private var shiftKeyView: TextView? = null
    private val letterLabels = mutableListOf<Pair<String, TextView>>()
    private val suggestionButtons = mutableListOf<Button>()

    private var spellSession: SpellCheckerSession? = null
    private var spellSequence = 1
    private val spellTargets = mutableMapOf<Int, SpellTarget>()
    private var shownSpellTarget: SpellTarget? = null
    private var shownSuggestions: List<String> = emptyList()

    private data class PendingCorrection(
        val word: String,
        val deletedText: String,
        val trailingText: String,
        val createdAtMs: Long,
    )

    private var undoPending: PendingCorrection? = null
    private var repeatPending: PendingCorrection? = null
    private var repeatLock: String? = null

    private var deleteDownAt = 0L
    private var deleteDownX = 0f
    private var deleteWordSwipeDone = false
    private var deleting = false

    private var spaceDownX = 0f
    private var spaceCursorStep = 0
    private var spaceDragging = false

    override fun onCreate() {
        super.onCreate()
        openSpellSession()
    }

    override fun onDestroy() {
        spellSession?.close()
        spellSession = null
        deleteHandler.removeCallbacksAndMessages(null)
        spellHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        activeEditorInfo = attribute
        clearTransientState()
        clearSuggestions()
        renderStatus()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        activeEditorInfo = info
        if (Prefs.prefs(this).getBoolean(Prefs.AUTO_CAPITALIZE, true)) updateAutoShift()
        renderStatus()
    }

    override fun onFinishInput() {
        clearTransientState()
        clearSuggestions()
        activeEditorInfo = null
        super.onFinishInput()
    }

    override fun onCreateInputView(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(2), dp(5), dp(5))
            setBackgroundColor(palette().keyboard)
            keyboardRoot = this
        }

        outer.addView(buildStatusRow(), fullWidthRow(dp(30)))
        outer.addView(buildSuggestionRow(), fullWidthRow(dp(44)))

        val holder = FrameLayout(this)
        mainPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emojiPanel = buildEmojiPanel().apply { visibility = View.GONE }
        holder.addView(mainPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        holder.addView(emojiPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(286)))
        outer.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        rebuildMainKeyboard()
        renderStatus()
        return outer
    }

    private fun buildStatusRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(palette().mutedText)
            setPadding(dp(7), 0, dp(6), 0)
        }
        statusView = status
        val undo = Button(this).apply {
            text = "↶ Undo"
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            background = rounded(palette().accentSoft, 10)
            setTextColor(palette().text)
            visibility = View.INVISIBLE
            isEnabled = false
            setOnClickListener { undoLastCorrection() }
        }
        undoButton = undo
        row.addView(status, LinearLayout.LayoutParams(0, dp(30), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        row.addView(undo, LinearLayout.LayoutParams(dp(92), dp(28)))
        return row
    }

    private fun buildSuggestionRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        suggestionButtons.clear()
        repeat(3) {
            val b = Button(this).apply {
                text = ""
                textSize = 15f
                isAllCaps = false
                minWidth = 0
                setPadding(dp(4), 0, dp(4), 0)
                setTextColor(palette().text)
                background = rounded(palette().suggestion, 12)
                isEnabled = false
            }
            suggestionButtons += b
            row.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        return row
    }

    private fun rebuildMainKeyboard() {
        val panel = mainPanel ?: return
        panel.removeAllViews()
        letterLabels.clear()
        shiftKeyView = null
        if (symbolMode) buildSymbolKeyboard(panel) else buildAlphabetKeyboard(panel)
    }

    private fun buildAlphabetKeyboard(panel: LinearLayout) {
        val hints = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        addLetterRow(panel, "qwertyuiop".mapIndexed { i, c -> c.toString() to hints[i] })
        addCenteredLetterRow(panel, "asdfghjkl".map { it.toString() to null })

        val third = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val shiftView = specialTextKey("⇧", palette().special) { toggleShift() }
        shiftKeyView = shiftView
        third.addView(shiftView, weightedKey(1.25f, dp(56)))
        "zxcvbnm".forEach { c -> third.addView(letterKey(c.toString(), null), weightedKey(1f, dp(56))) }
        val backspace = specialTextKey("⌫", palette().special) {}
        attachFastDelete(backspace)
        third.addView(backspace, weightedKey(1.25f, dp(56)))
        panel.addView(third, fullWidthRow(dp(58)))

        panel.addView(buildBottomRow(), fullWidthRow(dp(60)))
        refreshLetterLabels()
    }

    private fun buildSymbolKeyboard(panel: LinearLayout) {
        addSimpleRow(panel, listOf("1","2","3","4","5","6","7","8","9","0"))
        addSimpleRow(panel, listOf("@","#","£","_","&","-","+","(",")","/"))
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf("=","*","\"","'",":",";","!","?").forEach { s ->
            row3.addView(textKey(s) { commitPlain(s) }, weightedKey(1f, dp(56)))
        }
        val backspace = specialTextKey("⌫", palette().special) {}
        attachFastDelete(backspace)
        row3.addView(backspace, weightedKey(1.25f, dp(56)))
        panel.addView(row3, fullWidthRow(dp(58)))
        panel.addView(buildBottomRow(), fullWidthRow(dp(60)))
    }

    private fun buildBottomRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(specialTextKey(if (symbolMode) "ABC" else "?123", palette().special) {
            symbolMode = !symbolMode
            rebuildMainKeyboard()
        }, weightedKey(1.25f, dp(58)))
        row.addView(textKey(",") { commitPunctuation(",") }, weightedKey(.78f, dp(58)))
        row.addView(specialTextKey("😀", palette().special) { toggleEmojiPanel() }, weightedKey(.95f, dp(58)))
        val space = specialTextKey("space", palette().key) {}
        attachSpaceCursor(space)
        row.addView(space, weightedKey(3.6f, dp(58)))
        row.addView(textKey(".") { commitPunctuation(".") }, weightedKey(.78f, dp(58)))
        row.addView(specialTextKey("↵", palette().accentSoft) { handleEnter() }, weightedKey(1.18f, dp(58)))
        return row
    }

    private fun addLetterRow(parent: LinearLayout, keys: List<Pair<String,String?>>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        keys.forEach { (letter, hint) -> row.addView(letterKey(letter, hint), weightedKey(1f, dp(56))) }
        parent.addView(row, fullWidthRow(dp(58)))
    }

    private fun addCenteredLetterRow(parent: LinearLayout, keys: List<Pair<String,String?>>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(View(this), weightedKey(.45f, dp(56)))
        keys.forEach { (letter, hint) -> row.addView(letterKey(letter, hint), weightedKey(1f, dp(56))) }
        row.addView(View(this), weightedKey(.45f, dp(56)))
        parent.addView(row, fullWidthRow(dp(58)))
    }

    private fun addSimpleRow(parent: LinearLayout, keys: List<String>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        keys.forEach { key -> row.addView(textKey(key) { commitPlain(key) }, weightedKey(1f, dp(56))) }
        parent.addView(row, fullWidthRow(dp(58)))
    }

    private fun letterKey(letter: String, hint: String?): View {
        val frame = FrameLayout(this).apply {
            background = rounded(palette().key, 10)
            isClickable = true
            isFocusable = true
        }
        val main = TextView(this).apply {
            text = if (shift) letter.uppercase() else letter
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(palette().text)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        letterLabels += letter to main
        frame.addView(main, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (hint != null) {
            val small = TextView(this).apply {
                text = hint
                textSize = 10f
                gravity = Gravity.TOP or Gravity.END
                setPadding(0, dp(3), dp(6), 0)
                setTextColor(palette().mutedText)
            }
            frame.addView(small, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            frame.setOnLongClickListener {
                tapFeedback()
                commitPlain(hint)
                true
            }
        }
        frame.setOnClickListener {
            tapFeedback()
            val emitted = if (shift) letter.uppercase() else letter
            commitLetter(emitted)
            if (shift) {
                shift = false
                refreshLetterLabels()
            }
        }
        return frame
    }

    private fun textKey(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 21f
        gravity = Gravity.CENTER
        setTextColor(palette().text)
        background = rounded(palette().key, 10)
        isClickable = true
        isFocusable = true
        setOnClickListener { tapFeedback(); action() }
    }

    private fun specialTextKey(label: String, color: Int, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = if (label == "space") 14f else 20f
        gravity = Gravity.CENTER
        setTextColor(palette().text)
        background = rounded(color, 12)
        isClickable = true
        isFocusable = true
        setOnClickListener { tapFeedback(); action() }
    }

    private fun toggleShift() {
        clearImmediateUndo()
        shift = !shift
        refreshLetterLabels()
    }

    private fun refreshLetterLabels() {
        letterLabels.forEach { (base, view) -> view.text = if (shift) base.uppercase() else base }
        shiftKeyView?.background = rounded(if (shift) palette().accentSoft else palette().special, 12)
    }

    private fun commitLetter(text: String) {
        clearImmediateUndo()
        currentInputConnection?.commitText(text, 1)
        scheduleSpellCheck(SPELL_DEBOUNCE_MS)
    }

    private fun commitPlain(text: String) {
        clearImmediateUndo()
        clearSuggestions()
        currentInputConnection?.commitText(text, 1)
    }

    private fun handleSpace() {
        clearImmediateUndo()
        val ic = currentInputConnection ?: return
        val prefs = Prefs.prefs(this)
        if (prefs.getBoolean(Prefs.DOUBLE_SPACE_PERIOD, true)) {
            val before = ic.getTextBeforeCursor(3, 0)?.toString().orEmpty()
            if (before.length >= 2 && before.last() == ' ' && before[before.length - 2].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                clearSuggestions()
                updateAutoShift()
                return
            }
        }
        ic.commitText(" ", 1)
        inspectCompletedWord(forceWordComplete = false)
        maybeFallbackAutocorrect()
        scheduleSpellCheck(0)
        updateAutoShift()
    }

    private fun commitPunctuation(mark: String) {
        clearImmediateUndo()
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        if (before == " ") ic.deleteSurroundingText(1, 0)
        ic.commitText(mark, 1)
        inspectCompletedWord(forceWordComplete = false)
        scheduleSpellCheck(0)
        if (mark == "." || mark == "!" || mark == "?") updateAutoShift()
    }

    private fun handleEnter() {
        clearImmediateUndo()
        inspectCompletedWord(forceWordComplete = true)
        val ic = currentInputConnection ?: return
        val info = activeEditorInfo
        if (info == null) {
            ic.commitText("\n", 1)
        } else {
            val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            val actionable = action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
            if (!noEnterAction && actionable) ic.performEditorAction(action) else ic.commitText("\n", 1)
        }
        clearTransientState()
        clearSuggestions()
        updateAutoShift()
        renderStatus()
    }

    private fun attachFastDelete(view: View) {
        view.setOnClickListener(null)
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tapFeedback()
                    clearTransientState()
                    clearSuggestions()
                    deleting = true
                    deleteDownAt = SystemClock.elapsedRealtime()
                    deleteDownX = event.x
                    deleteWordSwipeDone = false
                    backspaceOnce()
                    deleteHandler.postDelayed(deleteRepeat, DELETE_INITIAL_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!deleteWordSwipeDone && event.x - deleteDownX < -dp(44)) {
                        deleteWordSwipeDone = true
                        deleteHandler.removeCallbacks(deleteRepeat)
                        deletePreviousWord()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    deleting = false
                    deleteHandler.removeCallbacks(deleteRepeat)
                    true
                }
                else -> true
            }
        }
    }

    private val deleteRepeat = object : Runnable {
        override fun run() {
            if (!deleting || deleteWordSwipeDone) return
            val elapsed = SystemClock.elapsedRealtime() - deleteDownAt
            val count = when {
                elapsed > 2600 -> 3
                elapsed > 1500 -> 2
                else -> 1
            }
            repeat(count) { backspaceOnce() }
            val delay = when {
                elapsed > 2600 -> 28L
                elapsed > 1200 -> 42L
                else -> 78L
            }
            deleteHandler.postDelayed(this, delay)
        }
    }

    private fun backspaceOnce() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingTextInCodePoints(1, 0)
        if (Prefs.prefs(this).getBoolean(Prefs.AUTO_CAPITALIZE, true)) updateAutoShift()
    }

    private fun deletePreviousWord() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(160, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        val match = Regex("""\s*\S+\s*$""").find(before) ?: return
        ic.deleteSurroundingText(match.value.length, 0)
        renderStatus("Deleted word")
    }

    private fun attachSpaceCursor(view: View) {
        view.setOnClickListener(null)
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    spaceDownX = event.x
                    spaceCursorStep = 0
                    spaceDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val step = ((event.x - spaceDownX) / dp(18).toFloat()).toInt()
                    val diff = (step - spaceCursorStep).coerceIn(-5, 5)
                    if (diff != 0) {
                        if (!spaceDragging) tapFeedback()
                        spaceDragging = true
                        moveCursor(diff)
                        spaceCursorStep += diff
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!spaceDragging) { tapFeedback(); handleSpace() }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    private fun moveCursor(delta: Int) {
        val ic = currentInputConnection ?: return
        val key = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(delta)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        }
        clearSuggestions()
    }

    private fun inspectCompletedWord(forceWordComplete: Boolean) {
        val ic = currentInputConnection ?: return
        val prefs = Prefs.prefs(this)
        val enabled = prefs.getBoolean(Prefs.ENABLED, true)
        if (!enabled) { clearTransientState(); renderStatus(); return }

        val prefix = ic.getTextBeforeCursor(MAX_CONTEXT_CHARS, 0)?.toString() ?: return
        val learned = prefs.getStringSet(Prefs.LEARNED_REPEATS, emptySet()).orEmpty()
        val decision = engine.decide(
            prefix = prefix,
            fieldContext = classifyField(activeEditorInfo),
            enabled = enabled,
            forceWordComplete = forceWordComplete,
            userProtectedWords = learned,
        )
        val lastWord = engine.lastWord(prefix)

        repeatLock?.let { locked ->
            if (lastWord == locked && decision.shouldCorrect) {
                renderStatus("Intentional repeat kept")
                return
            } else if (lastWord != null && lastWord != locked) repeatLock = null
        }

        if (decision.shouldCorrect) {
            val normalized = decision.word?.let(engine::normalizeWord)
            val pending = repeatPending
            if (normalized != null && pending != null && pending.word == normalized &&
                SystemClock.elapsedRealtime() - pending.createdAtMs <= REPEAT_WINDOW_MS) {
                restoreIntentionalRepeat(decision, pending)
                return
            }
            applyCorrection(decision)
            return
        }
        if (repeatPending != null) repeatPending = null
        renderStatus()
    }

    private fun applyCorrection(decision: CorrectionDecision) {
        val ic = currentInputConnection ?: return
        if (!decision.shouldCorrect || decision.tailLength <= 0 || decision.word == null) return
        var applied = false
        ic.beginBatchEdit()
        try {
            if (ic.deleteSurroundingText(decision.tailLength, 0)) {
                applied = decision.trailingText.isEmpty() || ic.commitText(decision.trailingText, 1)
                if (!applied) ic.commitText(decision.deletedText + decision.trailingText, 1)
            }
        } finally { ic.endBatchEdit() }
        if (!applied) { renderStatus("Correction unavailable in this field"); return }

        val pending = PendingCorrection(
            word = engine.normalizeWord(decision.word),
            deletedText = decision.deletedText,
            trailingText = decision.trailingText,
            createdAtMs = SystemClock.elapsedRealtime(),
        )
        undoPending = pending
        repeatPending = pending
        incrementCorrectionCount(1)
        tapFeedback()
        renderStatus("Removed duplicate “${decision.word}”")
    }

    private fun restoreIntentionalRepeat(currentDecision: CorrectionDecision, previous: PendingCorrection) {
        val ic = currentInputConnection ?: return
        var restored = false
        ic.beginBatchEdit()
        try {
            if (ic.deleteSurroundingText(currentDecision.tailLength, 0)) {
                restored = ic.commitText(previous.deletedText + currentDecision.deletedText + currentDecision.trailingText, 1)
                if (!restored) ic.commitText(currentDecision.deletedText + currentDecision.trailingText, 1)
            }
        } finally { ic.endBatchEdit() }
        if (!restored) { renderStatus("Repeat restoration unavailable"); return }
        incrementCorrectionCount(-1)
        repeatLock = previous.word
        repeatPending = null
        undoPending = null
        renderStatus("Intentional repeat kept")
    }

    private fun undoLastCorrection() {
        val pending = undoPending ?: return
        if (SystemClock.elapsedRealtime() - pending.createdAtMs > UNDO_WINDOW_MS) {
            undoPending = null; renderStatus(); return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(MAX_CONTEXT_CHARS, 0)?.toString() ?: return
        if (pending.trailingText.isNotEmpty() && !before.endsWith(pending.trailingText)) {
            undoPending = null; renderStatus(); return
        }
        ic.beginBatchEdit()
        try {
            if (pending.trailingText.isNotEmpty()) ic.deleteSurroundingText(pending.trailingText.length, 0)
            ic.commitText(pending.deletedText + pending.trailingText, 1)
        } finally { ic.endBatchEdit() }

        incrementCorrectionCount(-1)
        val prefs = Prefs.prefs(this)
        if (prefs.getBoolean(Prefs.LEARN_FROM_UNDO, true)) {
            val learned = prefs.getStringSet(Prefs.LEARNED_REPEATS, emptySet()).orEmpty().toMutableSet()
            learned += pending.word
            prefs.edit().putStringSet(Prefs.LEARNED_REPEATS, learned).apply()
        }
        undoPending = null
        repeatPending = null
        repeatLock = pending.word
        renderStatus("Restored · learned repeat")
    }

    private fun openSpellSession() {
        try {
            val manager = getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as TextServicesManager
            spellSession = manager.newSpellCheckerSession(null, Locale.getDefault(), this, true)
        } catch (_: Exception) {
            spellSession = null
        }
    }

    private fun scheduleSpellCheck(delayMs: Long) {
        if (!Prefs.prefs(this).getBoolean(Prefs.SPELLCHECK, true)) { clearSuggestions(); return }
        if (classifyField(activeEditorInfo) != FieldContext.NORMAL) { clearSuggestions(); return }
        spellHandler.removeCallbacks(spellRunnable)
        spellHandler.postDelayed(spellRunnable, delayMs)
    }

    private val spellRunnable = Runnable { requestSpellCheck() }

    private fun requestSpellCheck() {
        val ic = currentInputConnection ?: return
        val prefix = ic.getTextBeforeCursor(SPELL_CONTEXT_CHARS, 0)?.toString().orEmpty()
        val target = spellText.targetFromPrefix(prefix) ?: run { clearSuggestions(); return }
        if (target.word.length < 2) { clearSuggestions(); return }

        val fallback = spellText.fallbackSuggestions(target.word)
        if (fallback.isNotEmpty()) showSuggestions(target, fallback)

        val session = spellSession ?: return
        val seq = spellSequence++
        spellTargets[seq] = target
        @Suppress("DEPRECATION")
        session.getSuggestions(TextInfo(target.word, SPELL_COOKIE, seq), 3)
        if (spellTargets.size > 16) {
            val keepFrom = seq - 8
            spellTargets.keys.removeAll { it < keepFrom }
        }
    }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>) {
        if (results.isEmpty()) return
        val info = results.last()
        if (info.cookie != SPELL_COOKIE) return
        val target = spellTargets.remove(info.sequence) ?: return
        val inDictionary = (info.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) != 0
        val suggestions = mutableListOf<String>()
        for (i in 0 until info.suggestionsCount) {
            val s = info.getSuggestionAt(i)
            if (!s.isNullOrBlank() && !s.equals(target.word, ignoreCase = true) && s !in suggestions) suggestions += s
        }
        mainHandler.post {
            if (inDictionary && spellText.fallbackSuggestions(target.word).isEmpty()) {
                if (shownSpellTarget?.word == target.word) clearSuggestions()
            } else if (suggestions.isNotEmpty()) {
                val combined = (spellText.fallbackSuggestions(target.word) + suggestions).distinct().take(3)
                showSuggestions(target, combined)
            }
        }
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>) = Unit

    private fun showSuggestions(target: SpellTarget, suggestions: List<String>) {
        shownSpellTarget = target
        shownSuggestions = suggestions.take(3)
        suggestionButtons.forEachIndexed { index, b ->
            val text = shownSuggestions.getOrNull(index).orEmpty()
            b.text = text
            b.isEnabled = text.isNotEmpty()
            b.alpha = if (text.isNotEmpty()) 1f else .35f
            b.setOnClickListener(if (text.isNotEmpty()) View.OnClickListener {
                tapFeedback()
                applySpellingSuggestion(text)
            } else null)
        }
        if (suggestions.isNotEmpty()) statusView?.text = "Spelling · “${target.word}”"
    }

    private fun clearSuggestions() {
        shownSpellTarget = null
        shownSuggestions = emptyList()
        suggestionButtons.forEach { b -> b.text = ""; b.isEnabled = false; b.alpha = .35f; b.setOnClickListener(null) }
    }

    private fun applySpellingSuggestion(suggestion: String) {
        val target = shownSpellTarget ?: return
        val ic = currentInputConnection ?: return
        val prefix = ic.getTextBeforeCursor(SPELL_CONTEXT_CHARS, 0)?.toString().orEmpty()
        val current = spellText.targetFromPrefix(prefix)
        if (current == null || !current.word.equals(target.word, ignoreCase = true)) {
            clearSuggestions(); renderStatus("Suggestion expired"); return
        }
        ic.beginBatchEdit()
        try {
            if (ic.deleteSurroundingText(current.tailLength, 0)) {
                ic.commitText(spellText.replacementText(current, preserveCase(current.word, suggestion)), 1)
            }
        } finally { ic.endBatchEdit() }
        clearSuggestions()
        renderStatus("Spelling corrected")
        updateAutoShift()
    }

    private fun maybeFallbackAutocorrect() {
        val prefs = Prefs.prefs(this)
        if (!prefs.getBoolean(Prefs.AUTOCORRECT, false)) return
        if (classifyField(activeEditorInfo) != FieldContext.NORMAL) return
        val ic = currentInputConnection ?: return
        val prefix = ic.getTextBeforeCursor(SPELL_CONTEXT_CHARS, 0)?.toString().orEmpty()
        val target = spellText.targetFromPrefix(prefix) ?: return
        val suggestion = spellText.fallbackSuggestions(target.word).firstOrNull() ?: return
        ic.beginBatchEdit()
        try {
            if (ic.deleteSurroundingText(target.tailLength, 0)) {
                ic.commitText(spellText.replacementText(target, preserveCase(target.word, suggestion)), 1)
            }
        } finally { ic.endBatchEdit() }
        renderStatus("Corrected ${target.word} → $suggestion")
        clearSuggestions()
    }

    private fun preserveCase(original: String, suggestion: String): String {
        if (original.firstOrNull()?.isUpperCase() == true && suggestion.isNotEmpty()) {
            return suggestion.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
        return suggestion
    }

    private fun updateAutoShift() {
        if (!Prefs.prefs(this).getBoolean(Prefs.AUTO_CAPITALIZE, true)) return
        val ic = currentInputConnection ?: return
        val prefix = ic.getTextBeforeCursor(80, 0)?.toString().orEmpty()
        shift = prefix.isEmpty() || prefix.endsWith("\n") || Regex("""[.!?][ \t]+$""").containsMatchIn(prefix)
        refreshLetterLabels()
    }

    private fun buildEmojiPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            setBackgroundColor(palette().keyboard)
        }
        val categories = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf("🕘" to "recent", "😀" to "faces", "👍" to "people", "🐶" to "nature", "🍕" to "food", "⚽" to "activity", "❤️" to "symbols").forEach { (icon, id) ->
            categories.addView(specialTextKey(icon, palette().special) { showEmojiCategory(id) }, weightedKey(1f, dp(42)))
        }
        panel.addView(categories, fullWidthRow(dp(44)))

        val scroll = ScrollView(this).apply { tag = "emojiScroll" }
        val grid = GridLayout(this).apply {
            tag = "emojiGrid"
            columnCount = 8
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        scroll.addView(grid, ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(236)))
        mainHandler.post { showEmojiCategory("faces") }
        return panel
    }

    private fun toggleEmojiPanel() {
        emojiMode = !emojiMode
        mainPanel?.visibility = if (emojiMode) View.GONE else View.VISIBLE
        emojiPanel?.visibility = if (emojiMode) View.VISIBLE else View.GONE
        if (emojiMode) showEmojiCategory("recent")
    }

    private fun showEmojiCategory(id: String) {
        val panel = emojiPanel ?: return
        val grid = findTaggedView(panel, "emojiGrid") as? GridLayout ?: return
        grid.removeAllViews()
        val emojis = if (id == "recent") recentEmojis().ifEmpty { EMOJIS.getValue("faces") } else EMOJIS[id].orEmpty()
        emojis.take(64).forEach { emoji ->
            val key = TextView(this).apply {
                text = emoji
                textSize = 25f
                gravity = Gravity.CENTER
                background = rounded(Color.TRANSPARENT, 8)
                setOnClickListener {
                    tapFeedback()
                    clearImmediateUndo()
                    clearSuggestions()
                    currentInputConnection?.commitText(emoji, 1)
                    rememberEmoji(emoji)
                }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(46)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            grid.addView(key, lp)
        }
    }

    private fun findTaggedView(root: ViewGroup, tag: String): View? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.tag == tag) return child
            if (child is ViewGroup) findTaggedView(child, tag)?.let { return it }
        }
        return null
    }

    private fun recentEmojis(): List<String> {
        val raw = Prefs.prefs(this).getString(Prefs.RECENT_EMOJIS, "").orEmpty()
        return if (raw.isBlank()) emptyList() else raw.split(EMOJI_SEPARATOR).filter { it.isNotBlank() }
    }

    private fun rememberEmoji(emoji: String) {
        val recent = recentEmojis().toMutableList()
        recent.remove(emoji)
        recent.add(0, emoji)
        Prefs.prefs(this).edit().putString(Prefs.RECENT_EMOJIS, recent.take(32).joinToString(EMOJI_SEPARATOR)).apply()
    }

    private fun classifyField(info: EditorInfo?): FieldContext {
        if (info == null) return FieldContext.NORMAL
        val inputType = info.inputType
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> {
                val variation = inputType and InputType.TYPE_MASK_VARIATION
                if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) FieldContext.PASSWORD else FieldContext.NUMERIC
            }
            InputType.TYPE_CLASS_PHONE -> FieldContext.PHONE
            InputType.TYPE_CLASS_TEXT -> when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> FieldContext.PASSWORD
                InputType.TYPE_TEXT_VARIATION_URI -> FieldContext.URL
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldContext.EMAIL_ADDRESS
                else -> FieldContext.NORMAL
            }
            else -> FieldContext.NORMAL
        }
    }

    private fun clearImmediateUndo() {
        undoPending = null
        renderStatus()
    }

    private fun clearTransientState() {
        undoPending = null
        repeatPending = null
        repeatLock = null
    }

    private fun incrementCorrectionCount(delta: Long) {
        val prefs = Prefs.prefs(this)
        val current = prefs.getLong(Prefs.CORRECTION_COUNT, 0L)
        prefs.edit().putLong(Prefs.CORRECTION_COUNT, max(0L, current + delta)).apply()
    }

    private fun renderStatus(message: String? = null) {
        val prefs = Prefs.prefs(this)
        val enabled = prefs.getBoolean(Prefs.ENABLED, true)
        val count = prefs.getLong(Prefs.CORRECTION_COUNT, 0L)
        val showCount = prefs.getBoolean(Prefs.SHOW_COUNT, true)
        statusView?.text = message ?: buildString {
            append(if (enabled) "WordChecker · On" else "WordChecker · Off")
            if (showCount) append(" · $count fixes")
        }
        val canUndo = undoPending != null && SystemClock.elapsedRealtime() - (undoPending?.createdAtMs ?: 0L) <= UNDO_WINDOW_MS
        undoButton?.isEnabled = canUndo
        undoButton?.visibility = if (canUndo) View.VISIBLE else View.INVISIBLE
    }

    private fun tapFeedback() {
        if (Prefs.prefs(this).getBoolean(Prefs.HAPTIC, true)) keyboardRoot?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private data class Palette(val keyboard: Int, val key: Int, val special: Int, val suggestion: Int, val accentSoft: Int, val text: Int, val mutedText: Int)

    private fun palette(): Palette {
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (dark) Palette(
            Color.rgb(32, 34, 39), Color.rgb(62, 65, 72), Color.rgb(79, 82, 91), Color.rgb(48, 51, 57),
            Color.rgb(78, 91, 133), Color.WHITE, Color.rgb(195, 198, 207),
        ) else Palette(
            Color.rgb(239, 240, 245), Color.WHITE, Color.rgb(221, 224, 233), Color.rgb(248, 248, 250),
            Color.rgb(216, 224, 250), Color.rgb(25, 25, 28), Color.rgb(90, 92, 100),
        )
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun weightedKey(weight: Float, height: Int) = LinearLayout.LayoutParams(0, height, weight).apply {
        setMargins(dp(2), dp(2), dp(2), dp(2))
    }

    private fun fullWidthRow(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_CONTEXT_CHARS = 192
        private const val SPELL_CONTEXT_CHARS = 96
        private const val UNDO_WINDOW_MS = 10_000L
        private const val REPEAT_WINDOW_MS = 15_000L
        private const val DELETE_INITIAL_DELAY_MS = 330L
        private const val SPELL_DEBOUNCE_MS = 140L
        private const val SPELL_COOKIE = 7303
        private const val EMOJI_SEPARATOR = "|"

        private val EMOJIS = mapOf(
            "faces" to listOf("😀","😃","😄","😁","😆","😅","😂","🤣","😊","😇","🙂","🙃","😉","😌","😍","🥰","😘","😗","😙","😚","😋","😛","😝","😜","🤪","🤨","🧐","🤓","😎","🥳","😏","😒","😞","😔","😟","😕","🙁","☹️","😣","😖","😫","😩","🥺","😢","😭","😤","😠","😡","🤬","🤯","😳","🥵","🥶","😱","😨","😰","😥","😓","🤗","🤔","🫡","🤭","🫢","🤫"),
            "people" to listOf("👍","👎","👌","🤌","🤏","✌️","🤞","🫰","🤟","🤘","🤙","👈","👉","👆","👇","☝️","✋","🤚","🖐️","🖖","👋","🤝","👏","🙌","🫶","👐","🤲","🙏","✍️","💅","🤳","💪","🦾","🦵","🦶","👂","👃","🧠","🫀","🫁","🦷","👀","👁️","👅","👄","🫦"),
            "nature" to listOf("🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐻‍❄️","🐨","🐯","🦁","🐮","🐷","🐸","🐵","🙈","🙉","🙊","🐔","🐧","🐦","🐤","🦄","🐝","🪲","🦋","🐌","🐞","🐢","🐍","🦎","🐙","🦑","🦀","🐠","🐟","🐬","🐳","🌸","🌹","🌻","🌞","⭐","🌙","🌈","🔥","❄️","☀️","☁️"),
            "food" to listOf("🍏","🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🫐","🍈","🍒","🍑","🥭","🍍","🥥","🥝","🍅","🥑","🥦","🥕","🌽","🌶️","🍞","🥐","🥨","🧀","🥚","🍳","🥞","🧇","🍔","🍟","🍕","🌭","🥪","🌮","🌯","🍜","🍝","🍣","🍦","🍩","🍪","🎂","🍰","☕","🫖","🥤"),
            "activity" to listOf("⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉","🥏","🎱","🪀","🏓","🏸","🏒","🏑","🥍","🏏","🪃","🥅","⛳","🪁","🏹","🎣","🤿","🥊","🥋","🎽","🛹","🛼","🛷","⛸️","🥌","🎿","⛷️","🏂","🪂","🏋️","🤸","⛹️","🤺","🤾","🏌️","🏇","🧘","🎮","🎯","🎲","🎸","🎤","🎧"),
            "symbols" to listOf("❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❣️","💕","💞","💓","💗","💖","💘","💝","💟","☮️","✝️","☪️","🕉️","☸️","✡️","🔯","🕎","☯️","☦️","🛐","⛎","♈","♉","♊","♋","♌","♍","♎","♏","♐","♑","♒","♓","✅","❌","⚠️","💯","✨","🎉","🔥","⭐","💫","❗","❓","‼️","⁉️","➕","➖","✔️","☑️"),
        )
    }
}

package com.cornelius.wordchecker.ime

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.cornelius.wordchecker.Prefs
import com.cornelius.wordchecker.core.CorrectionDecision
import com.cornelius.wordchecker.core.FieldContext
import com.cornelius.wordchecker.core.SafeDeleteEngine
import kotlin.math.max

class WordCheckerImeService : InputMethodService() {
    private val engine = SafeDeleteEngine()
    private var activeEditorInfo: EditorInfo? = null
    private var shift = false

    private var statusView: TextView? = null
    private var undoButton: Button? = null
    private var keyboardRoot: LinearLayout? = null

    private data class PendingCorrection(
        val word: String,
        val deletedText: String,
        val trailingText: String,
        val createdAtMs: Long,
    )

    /** Immediate user-facing undo. Cleared as soon as the user continues editing. */
    private var undoPending: PendingCorrection? = null

    /** Kept a little longer so "blue blue blue" can be restored as intentional. */
    private var repeatPending: PendingCorrection? = null
    private var repeatLock: String? = null

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        activeEditorInfo = attribute
        clearTransientState()
        renderStatus()
    }

    override fun onFinishInput() {
        clearTransientState()
        activeEditorInfo = null
        super.onFinishInput()
    }

    override fun onCreateInputView(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(6))
            keyboardRoot = this
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val localStatusView = TextView(this).apply {
            textSize = 13f
            setPadding(dp(8), 0, dp(8), 0)
        }
        statusView = localStatusView
        val localUndoButton = Button(this).apply {
            text = "Undo"
            minWidth = 0
            isAllCaps = false
            isEnabled = false
            visibility = View.INVISIBLE
            setOnClickListener { undoLastCorrection() }
        }
        undoButton = localUndoButton
        statusRow.addView(
            localStatusView,
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { gravity = Gravity.CENTER_VERTICAL },
        )
        statusRow.addView(
            localUndoButton,
            LinearLayout.LayoutParams(dp(88), dp(40)),
        )
        outer.addView(statusRow, fullWidthRow(dp(40)))

        addRow(outer, listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
        addRow(outer, listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"))
        addRow(outer, listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"))

        val third = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        third.addView(specialKey("⇧", 1.15f) { toggleShift() })
        listOf("z", "x", "c", "v", "b", "n", "m").forEach { key ->
            third.addView(letterKey(key), weightedKey(1f))
        }
        third.addView(specialKey("⌫", 1.15f) { backspace() })
        outer.addView(third, fullWidthRow(dp(52)))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(textKey(",", 0.85f) { commitCompletion(",") })
        bottom.addView(textKey("'", 0.85f) { commitPlain("'") })
        bottom.addView(textKey("space", 3.8f) { commitCompletion(" ") })
        bottom.addView(textKey(".", 0.85f) { commitCompletion(".") })
        bottom.addView(textKey("?", 0.85f) { commitCompletion("?") })
        bottom.addView(specialKey("↵", 1.15f) { handleEnter() })
        outer.addView(bottom, fullWidthRow(dp(56)))

        renderStatus()
        return outer
    }

    private fun addRow(parent: LinearLayout, keys: List<String>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys.forEach { key ->
            row.addView(
                if (key.length == 1 && key[0].isLetter()) letterKey(key)
                else textKey(key, 1f) { commitPlain(key) },
                weightedKey(1f),
            )
        }
        parent.addView(row, fullWidthRow(dp(52)))
    }

    private fun letterKey(letter: String): Button = Button(this).apply {
        tag = letter
        text = if (shift) letter.uppercase() else letter
        textSize = 18f
        minWidth = 0
        isAllCaps = false
        setPadding(0, 0, 0, 0)
        setOnClickListener {
            val base = tag as String
            val emitted = if (shift) base.uppercase() else base
            commitPlain(emitted)
            if (shift) {
                shift = false
                refreshLetterLabels()
            }
        }
    }

    private fun textKey(label: String, weight: Float, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = if (label == "space") 14f else 18f
        minWidth = 0
        isAllCaps = false
        setPadding(0, 0, 0, 0)
        setOnClickListener { action() }
        layoutParams = weightedKey(weight)
    }

    private fun specialKey(label: String, weight: Float, action: () -> Unit): Button =
        textKey(label, weight, action)

    private fun toggleShift() {
        clearImmediateUndo()
        shift = !shift
        refreshLetterLabels()
    }

    private fun refreshLetterLabels() {
        fun visit(view: View) {
            if (view is Button && view.tag is String) {
                val base = view.tag as String
                view.text = if (shift) base.uppercase() else base
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
        }
        keyboardRoot?.let(::visit)
    }

    private fun commitPlain(text: String) {
        clearImmediateUndo()
        currentInputConnection?.commitText(text, 1)
    }

    private fun commitCompletion(text: String) {
        clearImmediateUndo()
        currentInputConnection?.commitText(text, 1)
        inspectCompletedWord(forceWordComplete = false)
    }

    private fun backspace() {
        clearTransientState()
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingTextInCodePoints(1, 0)
        }
        renderStatus()
    }

    private fun handleEnter() {
        clearImmediateUndo()
        inspectCompletedWord(forceWordComplete = true)

        val ic = currentInputConnection ?: return
        val info = activeEditorInfo
        if (info == null) {
            ic.commitText("\n", 1)
            return
        }

        val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val actionable = action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED

        if (!noEnterAction && actionable) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
        clearTransientState()
        renderStatus()
    }

    private fun inspectCompletedWord(forceWordComplete: Boolean) {
        val ic = currentInputConnection ?: return
        val enabled = Prefs.prefs(this).getBoolean(Prefs.ENABLED, true)
        if (!enabled) {
            clearTransientState()
            renderStatus()
            return
        }

        val prefix = ic.getTextBeforeCursor(MAX_CONTEXT_CHARS, 0)?.toString() ?: return
        val context = classifyField(activeEditorInfo)
        val decision = engine.decide(
            prefix = prefix,
            fieldContext = context,
            enabled = enabled,
            forceWordComplete = forceWordComplete,
        )
        val lastWord = engine.lastWord(prefix)

        repeatLock?.let { locked ->
            if (lastWord == locked) {
                if (decision.shouldCorrect) {
                    showMessage("Intentional repeat kept")
                    return
                }
            } else if (lastWord != null) {
                repeatLock = null
            }
        }

        if (decision.shouldCorrect) {
            val normalized = decision.word?.let(engine::normalizeWord)
            val pending = repeatPending
            if (normalized != null && pending != null &&
                pending.word == normalized &&
                SystemClock.elapsedRealtime() - pending.createdAtMs <= REPEAT_WINDOW_MS
            ) {
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
            val deleted = ic.deleteSurroundingText(decision.tailLength, 0)
            if (deleted) {
                val committed = decision.trailingText.isEmpty() ||
                    ic.commitText(decision.trailingText, 1)
                if (committed) {
                    applied = true
                } else {
                    ic.commitText(decision.deletedText + decision.trailingText, 1)
                }
            }
        } finally {
            ic.endBatchEdit()
        }
        if (!applied) {
            renderStatus("Correction unavailable in this field")
            return
        }

        val pending = PendingCorrection(
            word = engine.normalizeWord(decision.word),
            deletedText = decision.deletedText,
            trailingText = decision.trailingText,
            createdAtMs = SystemClock.elapsedRealtime(),
        )
        undoPending = pending
        repeatPending = pending
        incrementCorrectionCount(1)

        if (Prefs.prefs(this).getBoolean(Prefs.HAPTIC, true)) {
            keyboardRoot?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        renderStatus("Removed duplicate “${decision.word}”")
    }

    private fun restoreIntentionalRepeat(
        currentDecision: CorrectionDecision,
        previous: PendingCorrection,
    ) {
        val ic = currentInputConnection ?: return
        var restored = false
        ic.beginBatchEdit()
        try {
            val deleted = ic.deleteSurroundingText(currentDecision.tailLength, 0)
            if (deleted) {
                restored = ic.commitText(
                    previous.deletedText + currentDecision.deletedText + currentDecision.trailingText,
                    1,
                )
                if (!restored) {
                    ic.commitText(currentDecision.deletedText + currentDecision.trailingText, 1)
                }
            }
        } finally {
            ic.endBatchEdit()
        }
        if (!restored) {
            renderStatus("Repeat restoration unavailable")
            return
        }

        incrementCorrectionCount(-1)
        repeatLock = previous.word
        repeatPending = null
        undoPending = null
        renderStatus("Intentional repeat kept")
    }

    private fun undoLastCorrection() {
        val pending = undoPending ?: return
        if (SystemClock.elapsedRealtime() - pending.createdAtMs > UNDO_WINDOW_MS) {
            undoPending = null
            renderStatus()
            return
        }

        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(MAX_CONTEXT_CHARS, 0)?.toString() ?: return
        if (pending.trailingText.isNotEmpty() && !before.endsWith(pending.trailingText)) {
            undoPending = null
            renderStatus()
            return
        }

        ic.beginBatchEdit()
        try {
            if (pending.trailingText.isNotEmpty()) {
                ic.deleteSurroundingText(pending.trailingText.length, 0)
            }
            ic.commitText(pending.deletedText + pending.trailingText, 1)
        } finally {
            ic.endBatchEdit()
        }

        incrementCorrectionCount(-1)
        undoPending = null
        repeatPending = null
        repeatLock = pending.word
        renderStatus("Restored")
    }

    private fun classifyField(info: EditorInfo?): FieldContext {
        if (info == null) return FieldContext.NORMAL
        val inputType = info.inputType
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> {
                val variation = inputType and InputType.TYPE_MASK_VARIATION
                if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) FieldContext.PASSWORD
                else FieldContext.NUMERIC
            }
            InputType.TYPE_CLASS_PHONE -> FieldContext.PHONE
            InputType.TYPE_CLASS_TEXT -> {
                when (inputType and InputType.TYPE_MASK_VARIATION) {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> FieldContext.PASSWORD
                    InputType.TYPE_TEXT_VARIATION_URI -> FieldContext.URL
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldContext.EMAIL_ADDRESS
                    else -> FieldContext.NORMAL
                }
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

        val canUndo = undoPending != null &&
            SystemClock.elapsedRealtime() - (undoPending?.createdAtMs ?: 0L) <= UNDO_WINDOW_MS
        undoButton?.isEnabled = canUndo
        undoButton?.visibility = if (canUndo) View.VISIBLE else View.INVISIBLE
    }

    private fun showMessage(message: String) = renderStatus(message)

    private fun weightedKey(weight: Float) = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.MATCH_PARENT,
        weight,
    ).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) }

    private fun fullWidthRow(height: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        height,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_CONTEXT_CHARS = 192
        private const val UNDO_WINDOW_MS = 5_000L
        private const val REPEAT_WINDOW_MS = 15_000L
    }
}

package com.namblue.live

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import com.namblue.live.databinding.DialogConfirmBinding
import com.namblue.live.databinding.DialogSearchBinding

/** Remote-friendly popups in the app's own style (rounded dark panel, big focusable buttons). */
object TvDialogs {

    private const val WIDTH_DP = 560

    /**
     * Yes/no popup. Focus starts on "Huỷ" so pressing OK again by reflex never confirms;
     * only the explicit [confirmLabel] button runs [onConfirm].
     */
    fun confirm(activity: Activity, message: String, confirmLabel: String, onConfirm: () -> Unit): Dialog {
        val binding = DialogConfirmBinding.inflate(activity.layoutInflater)
        val dialog = panel(activity, binding.root)
        binding.confirmMessage.text = message
        binding.btnConfirm.text = confirmLabel
        binding.btnCancel.setOnClickListener { dialog.dismiss() }
        binding.btnConfirm.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        dialog.show()
        binding.btnCancel.requestFocus()
        return dialog
    }

    /** Text prompt for a search keyword; [onSearch] gets the trimmed, non-blank query. */
    fun search(activity: Activity, initial: String, onSearch: (String) -> Unit): Dialog {
        val binding = DialogSearchBinding.inflate(activity.layoutInflater)
        val dialog = panel(activity, binding.root)
        val submit = {
            val query = binding.searchInput.text?.toString()?.trim().orEmpty()
            if (query.isNotEmpty()) {
                dialog.dismiss()
                onSearch(query)
            }
        }
        binding.searchInput.setText(initial)
        binding.searchInput.setSelection(initial.length)
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            val isSubmit = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
            if (isSubmit) submit()
            isSubmit
        }
        binding.btnCancel.setOnClickListener { dialog.dismiss() }
        binding.btnSearch.setOnClickListener { submit() }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        binding.searchInput.requestFocus()
        return dialog
    }

    private fun panel(activity: Activity, content: View): Dialog = Dialog(activity).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(content)
        // The layout draws its own rounded panel; drop the stock dialog frame around it.
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val width = (WIDTH_DP * activity.resources.displayMetrics.density).toInt()
        window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

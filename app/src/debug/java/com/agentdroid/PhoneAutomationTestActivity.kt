package com.agentdroid

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Debug-only target app fixture used by Accessibility end-to-end tests. */
class PhoneAutomationTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val input = EditText(this).apply { hint = "Input"; contentDescription = "agentdroid_e2e_input" }
        val button = Button(this).apply { text = "Submit"; contentDescription = "agentdroid_e2e_submit" }
        val result = TextView(this).apply { text = "WAITING"; contentDescription = "agentdroid_e2e_result" }
        button.setOnClickListener { result.text = "AGENTDROID_PHONE_OK:${input.text}" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
            addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(button, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(result, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }
}

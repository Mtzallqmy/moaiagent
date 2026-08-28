package com.agentdroid.core.phone

class PhoneActionVerifier {
    fun verify(action: PhoneAction, before: ScreenState, after: ScreenState): Boolean = when (action.type) {
        PhoneActionType.PRESS_HOME, PhoneActionType.PRESS_BACK, PhoneActionType.OPEN_APP ->
            before.packageName != after.packageName || before.fingerprint != after.fingerprint
        PhoneActionType.TYPE_TEXT -> action.text.orEmpty().let { expected ->
            expected.isNotEmpty() && after.flatten().any { it.text?.contains(expected) == true }
        }
        PhoneActionType.CLEAR_TEXT -> {
            val id = action.elementId
            id != null && after.flatten().firstOrNull { it.elementId == id }?.text.orEmpty().isEmpty()
        }
        PhoneActionType.TAKE_SCREENSHOT -> after.screenshotPath != null
        else -> before.fingerprint != after.fingerprint || before.packageName != after.packageName
    }
}

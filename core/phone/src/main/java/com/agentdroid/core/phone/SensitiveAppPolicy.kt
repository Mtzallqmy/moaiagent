package com.agentdroid.core.phone

import com.agentdroid.core.agent.RiskLevel

class SensitiveAppPolicy {
    enum class Category { BANKING, CRYPTO_WALLET, AUTHENTICATOR, PASSWORD_MANAGER, DEVICE_ADMIN, PACKAGE_INSTALLER }

    data class Classification(val category: Category, val reason: String)
    data class Decision(val classification: Classification?, val blocked: Boolean, val risk: RiskLevel, val reason: String?)

    private val rules = listOf(
        Category.PACKAGE_INSTALLER to listOf("packageinstaller", "permissioncontroller", "installer"),
        Category.DEVICE_ADMIN to listOf("devicepolicy", "deviceadmin", "mdm", "settings"),
        Category.AUTHENTICATOR to listOf("authenticator", "authy", "aegis", "2fa", "otp"),
        Category.PASSWORD_MANAGER to listOf("1password", "bitwarden", "keepass", "lastpass", "password"),
        Category.CRYPTO_WALLET to listOf("metamask", "trustwallet", "coinbase.wallet", "crypto.wallet", "wallet"),
        Category.BANKING to listOf("bank", "banking", "paypal", "venmo", "revolut", "wise")
    )

    fun classify(packageName: String?, label: String? = null): Classification? {
        val haystack = listOfNotNull(packageName, label).joinToString(" ").lowercase()
        if (haystack.isBlank()) return null
        val match = rules.firstOrNull { (_, terms) -> terms.any { it in haystack } } ?: return null
        return Classification(match.first, "Sensitive application category: ${match.first.name}")
    }

    fun evaluate(packageName: String?, label: String? = null, overrideSensitive: Boolean, baseRisk: RiskLevel): Decision {
        val classification = classify(packageName, label)
        if (classification == null) return Decision(null, false, baseRisk, null)
        return if (overrideSensitive) {
            Decision(classification, false, RiskLevel.SENSITIVE, classification.reason)
        } else {
            Decision(classification, true, RiskLevel.SENSITIVE, "Blocked by default. Set overrideSensitive=true and approve the sensitive action explicitly.")
        }
    }
}

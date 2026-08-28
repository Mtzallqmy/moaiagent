package com.agentdroid.core.phone

import com.agentdroid.core.agent.AgentError
import com.agentdroid.core.agent.AgentErrorCode
import com.agentdroid.core.agent.AgentTool
import com.agentdroid.core.agent.RiskLevel
import com.agentdroid.core.agent.ToolCategory
import com.agentdroid.core.agent.ToolContext
import com.agentdroid.core.agent.ToolDefinition
import com.agentdroid.core.agent.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class PhoneServices(
    val engine: PhoneAutomationEngine,
    val sensitivePolicy: SensitiveAppPolicy = SensitiveAppPolicy()
)

fun createPhoneAgentTools(services: PhoneServices): List<AgentTool> = listOf(
    StateTool(services, false), StateTool(services, true), ListAppsTool(services), WaitElementTool(services),
    ActionTool(services, "tap_element", PhoneActionType.TAP_ELEMENT, RiskLevel.MODIFY),
    ActionTool(services, "tap_coordinates", PhoneActionType.TAP_COORDINATES, RiskLevel.MODIFY),
    ActionTool(services, "long_press", PhoneActionType.LONG_PRESS, RiskLevel.MODIFY),
    ActionTool(services, "swipe", PhoneActionType.SWIPE, RiskLevel.MODIFY),
    ActionTool(services, "scroll", PhoneActionType.SCROLL, RiskLevel.MODIFY),
    ActionTool(services, "type_text", PhoneActionType.TYPE_TEXT, RiskLevel.MODIFY),
    ActionTool(services, "clear_text", PhoneActionType.CLEAR_TEXT, RiskLevel.MODIFY),
    ActionTool(services, "press_back", PhoneActionType.PRESS_BACK, RiskLevel.MODIFY),
    ActionTool(services, "press_home", PhoneActionType.PRESS_HOME, RiskLevel.MODIFY),
    ActionTool(services, "open_app", PhoneActionType.OPEN_APP, RiskLevel.EXTERNAL),
    ActionTool(services, "take_screenshot", PhoneActionType.TAKE_SCREENSHOT, RiskLevel.SENSITIVE)
)

private val json = Json { encodeDefaults = true }
private fun schema(properties: Map<String, String>, required: List<String> = emptyList()): JsonObject = JsonObject(buildMap {
    put("type", JsonPrimitive("object"))
    put("properties", JsonObject(properties.mapValues { JsonObject(mapOf("type" to JsonPrimitive(it.value))) }))
    if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
})

private abstract class BasePhoneTool(protected val services: PhoneServices) : AgentTool {
    override fun availableInMode(mode: com.agentdroid.core.agent.AgentMode): Boolean = mode == com.agentdroid.core.agent.AgentMode.AGENT

    protected suspend fun sensitiveDecision(input: JsonObject, targetPackage: String?, baseRisk: RiskLevel): SensitiveAppPolicy.Decision {
        val current = targetPackage ?: services.engine.captureState(false).packageName
        val override = input["overrideSensitive"]?.jsonPrimitive?.booleanOrNull ?: false
        return services.sensitivePolicy.evaluate(current, overrideSensitive = override, baseRisk = baseRisk)
    }

    protected fun blocked(decision: SensitiveAppPolicy.Decision): ToolResult = ToolResult.failure(
        AgentError(AgentErrorCode.PERMISSION_DENIED, decision.reason ?: "Sensitive app blocked", decision.reason ?: "Sensitive application action blocked.", false)
    )
}

private class StateTool(services: PhoneServices, private val treeOnly: Boolean) : BasePhoneTool(services) {
    override val definition = ToolDefinition(
        if (treeOnly) "get_accessibility_tree" else "get_screen_state",
        if (treeOnly) "Read the current Android accessibility tree with semantic element IDs." else "Read the current Android package/activity and semantic UI state.",
        schema(mapOf("includeScreenshot" to "boolean", "overrideSensitive" to "boolean")), RiskLevel.SAFE, ToolCategory.EXTERNAL
    )
    override suspend fun effectiveRisk(input: JsonObject, context: ToolContext): RiskLevel = sensitiveDecision(input, null, RiskLevel.SAFE).risk
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val decision = sensitiveDecision(input, null, RiskLevel.SAFE); if (decision.blocked) return blocked(decision)
        val includeScreenshot = !treeOnly && (input["includeScreenshot"]?.jsonPrimitive?.booleanOrNull ?: false)
        val state = services.engine.captureState(includeScreenshot)
        return ToolResult.success("Captured phone state", json.encodeToJsonElement(ScreenState.serializer(), state).jsonObject)
    }
}

private class ListAppsTool(services: PhoneServices) : BasePhoneTool(services) {
    override val definition = ToolDefinition("list_apps", "List launchable Android applications.", schema(emptyMap()), RiskLevel.SAFE, ToolCategory.EXTERNAL)
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val apps = services.engine.listApps()
        val serializer = kotlinx.serialization.builtins.ListSerializer(InstalledApp.serializer())
        return ToolResult.success("Found ${apps.size} launchable apps", JsonObject(mapOf("apps" to json.encodeToJsonElement(serializer, apps))))
    }
}

private class WaitElementTool(services: PhoneServices) : BasePhoneTool(services) {
    override val definition = ToolDefinition("wait_for_element", "Wait for an Android UI element by semantic ID, text, description, or resource ID.", schema(mapOf("query" to "string", "timeoutMs" to "integer", "overrideSensitive" to "boolean"), listOf("query")), RiskLevel.SAFE, ToolCategory.EXTERNAL)
    override suspend fun effectiveRisk(input: JsonObject, context: ToolContext): RiskLevel = sensitiveDecision(input, null, RiskLevel.SAFE).risk
    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val decision = sensitiveDecision(input, null, RiskLevel.SAFE); if (decision.blocked) return blocked(decision)
        val query = input["query"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.failure(AgentError.validation("query is required"))
        val timeout = input["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 5_000
        val node = services.engine.waitForElement(query, timeout) ?: return ToolResult.failure(AgentError(AgentErrorCode.ELEMENT_NOT_FOUND, "Element not found: $query", "The requested phone element was not found.", true))
        return ToolResult.success("Element found", json.encodeToJsonElement(UiNode.serializer(), node).jsonObject)
    }
}

private class ActionTool(
    services: PhoneServices,
    private val toolName: String,
    private val actionType: PhoneActionType,
    private val baseRisk: RiskLevel
) : BasePhoneTool(services) {
    override val definition = ToolDefinition(
        toolName, "Perform Android phone action ${actionType.name.lowercase()} and verify the resulting screen state.",
        schema(mapOf("elementId" to "string", "x" to "integer", "y" to "integer", "endX" to "integer", "endY" to "integer", "text" to "string", "packageName" to "string", "durationMs" to "integer", "overrideSensitive" to "boolean")),
        baseRisk, ToolCategory.EXTERNAL
    )

    override suspend fun effectiveRisk(input: JsonObject, context: ToolContext): RiskLevel {
        val target = if (actionType == PhoneActionType.OPEN_APP) input["packageName"]?.jsonPrimitive?.contentOrNull else null
        return sensitiveDecision(input, target, baseRisk).risk
    }

    override suspend fun permissionKey(input: JsonObject, context: ToolContext): String? = when (actionType) {
        PhoneActionType.OPEN_APP -> input["packageName"]?.jsonPrimitive?.contentOrNull?.let { "$toolName:$it" }
        else -> toolName
    }

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val target = if (actionType == PhoneActionType.OPEN_APP) input["packageName"]?.jsonPrimitive?.contentOrNull else null
        val decision = sensitiveDecision(input, target, baseRisk); if (decision.blocked) return blocked(decision)
        val action = PhoneAction(
            type = actionType,
            elementId = input["elementId"]?.jsonPrimitive?.contentOrNull,
            x = input["x"]?.jsonPrimitive?.intOrNull,
            y = input["y"]?.jsonPrimitive?.intOrNull,
            endX = input["endX"]?.jsonPrimitive?.intOrNull,
            endY = input["endY"]?.jsonPrimitive?.intOrNull,
            text = input["text"]?.jsonPrimitive?.contentOrNull,
            packageName = input["packageName"]?.jsonPrimitive?.contentOrNull,
            durationMs = input["durationMs"]?.jsonPrimitive?.longOrNull ?: 300
        )
        val validation = validate(action); if (validation != null) return ToolResult.failure(AgentError.validation(validation))
        val result = services.engine.perform(action, 2)
        val output = json.encodeToJsonElement(PhoneActionResult.serializer(), result).jsonObject
        return if (result.success) ToolResult.success(result.summary, output) else ToolResult.failure(AgentError(AgentErrorCode.ELEMENT_NOT_INTERACTABLE, result.error ?: result.summary, result.summary, true), output)
    }

    private fun validate(action: PhoneAction): String? = when (action.type) {
        PhoneActionType.TAP_ELEMENT, PhoneActionType.SCROLL, PhoneActionType.TYPE_TEXT, PhoneActionType.CLEAR_TEXT -> if (action.elementId.isNullOrBlank()) "elementId is required" else if (action.type == PhoneActionType.TYPE_TEXT && action.text == null) "text is required" else null
        PhoneActionType.TAP_COORDINATES, PhoneActionType.LONG_PRESS -> if (action.x == null || action.y == null) "x and y are required" else null
        PhoneActionType.SWIPE -> if (action.x == null || action.y == null || action.endX == null || action.endY == null) "x, y, endX and endY are required" else null
        PhoneActionType.OPEN_APP -> if (action.packageName.isNullOrBlank()) "packageName is required" else null
        else -> null
    }
}

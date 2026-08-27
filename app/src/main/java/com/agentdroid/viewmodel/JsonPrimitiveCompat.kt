package com.agentdroid.viewmodel

import kotlinx.serialization.json.JsonPrimitive

/** Local compatibility helper so ViewModels can safely read optional JSON string values. */
internal val JsonPrimitive.contentOrNull: String?
    get() = runCatching { content }.getOrNull()

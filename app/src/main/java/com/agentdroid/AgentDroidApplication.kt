package com.agentdroid

import android.app.Application

class AgentDroidApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

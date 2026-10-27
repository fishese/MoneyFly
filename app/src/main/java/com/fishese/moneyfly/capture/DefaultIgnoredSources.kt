package com.fishese.moneyfly.capture

/** Exact package names only. Financial, messaging and shared system services stay capturable. */
object DefaultIgnoredSources {
    val packages = setOf(
        "com.instagram.android", "com.openai.chatgpt", "ai.x.grok", "ai.x.grok.bot",
        "com.microsoft.appmanager", "com.tailscale.ipn",
        "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera",
        "com.android.deskclock", "com.google.android.deskclock", "com.sec.android.app.clockpackage",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.samsung.android.app.soundassistant", "com.samsung.android.app.smartcapture",
        "com.samsung.android.scloud", "com.wssyncmldm", "com.sec.android.soagent",
        "com.sec.android.daemonapp", "com.samsung.android.weather",
    )
}

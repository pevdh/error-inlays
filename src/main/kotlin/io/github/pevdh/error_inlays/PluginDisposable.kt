package io.github.pevdh.error_inlays

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager

class PluginDisposable : Disposable {
    companion object {
        val instance: PluginDisposable
            get() = ApplicationManager.getApplication().getService(PluginDisposable::class.java)
    }

    override fun dispose() {
    }
}
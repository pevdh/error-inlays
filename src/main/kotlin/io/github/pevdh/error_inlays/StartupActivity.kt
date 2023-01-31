package io.github.pevdh.error_inlays

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

private val LOGGER = Logger.getInstance(StartupActivity::class.java)
private val ERROR_INLAY_MANAGER_INSTANCE_KEY: Key<ErrorInlayManager> = Key(ErrorInlayManager::class.java.name)

class StartupActivity : StartupActivity {

    override fun runActivity(project: Project) {
        val editorFactory = EditorFactory.getInstance()
        editorFactory
            .addEditorFactoryListener(object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    LOGGER.debug("Editor created, attaching new error manager")

                    installManager(event.editor)
                }

                override fun editorReleased(event: EditorFactoryEvent) {
                    LOGGER.debug("Editor released, disposing associated error manager")

                    disposeManager(event.editor)
                }
            }, PluginDisposable.instance)

        editorFactory.allEditors.forEach { editor -> installManager(editor) }
    }

    private fun installManager(editor: Editor) {
        val manager = ErrorInlayManager.tryCreateNewInstance(editor) ?: return
        editor.putUserData(ERROR_INLAY_MANAGER_INSTANCE_KEY, manager)
    }

    private fun disposeManager(editor: Editor) {
        val manager = editor.getUserData(ERROR_INLAY_MANAGER_INSTANCE_KEY) ?: return
        Disposer.dispose(manager)

        editor.putUserData(ERROR_INLAY_MANAGER_INSTANCE_KEY, null)
    }
}

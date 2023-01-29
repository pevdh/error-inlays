package io.github.pevdh.error_lens

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
private val ERROR_LENS_INSTANCE_KEY: Key<ErrorLens> = Key<ErrorLens>(ErrorLens::class.java.name)

class StartupActivity : StartupActivity {

    override fun runActivity(project: Project) {
        val editorFactory = EditorFactory.getInstance()
        editorFactory
            .addEditorFactoryListener(object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    LOGGER.debug("Editor created, attaching new error lens")

                    installErrorLens(event.editor)
                }

                override fun editorReleased(event: EditorFactoryEvent) {
                    LOGGER.debug("Editor released, disposing associated error lens")

                    disposeErrorLens(event.editor)
                }
            }, PluginDisposable.instance)

        editorFactory.allEditors.forEach { editor -> installErrorLens(editor) }
    }

    private fun installErrorLens(editor: Editor) {
        val lens = ErrorLens.tryCreateNewInstance(editor) ?: return
        editor.putUserData(ERROR_LENS_INSTANCE_KEY, lens)
    }

    private fun disposeErrorLens(editor: Editor) {
        val lens = editor.getUserData(ERROR_LENS_INSTANCE_KEY) ?: return
        Disposer.dispose(lens)

        editor.putUserData(ERROR_LENS_INSTANCE_KEY, null)
    }
}

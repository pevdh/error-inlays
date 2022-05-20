package io.github.pevdh.error_lens

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.impl.event.MarkupModelListener
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.jetbrains.rd.util.concurrentMapOf
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle
import java.util.*

private val logger = Logger.getInstance("io.github.pevdh.error_lens")

class ErrorLensStartupActivity : StartupActivity {
    private val lenses: MutableMap<Editor, ErrorLens> = concurrentMapOf()
    private val settings = ErrorLensSettings.instance

    override fun runActivity(project: Project) {
        val editorFactory = EditorFactory.getInstance()
        editorFactory.allEditors.forEach { attachToEditor(it) }
        editorFactory
            .addEditorFactoryListener(object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    attachToEditor(event.editor)
                }

                override fun editorReleased(event: EditorFactoryEvent) {
                    val lens = lenses[event.editor] ?: return
                    Disposer.dispose(lens)
                    lenses.remove(event.editor)
                }
            }, project)
    }

    private fun attachToEditor(editor: Editor) {
        lenses[editor] = createErrorLens(editor) ?: return
    }

    private fun createErrorLens(editor: Editor): ErrorLens? {
        val project = editor.project ?: return null
        val document = editor.document
        val markupModel = DocumentMarkupModel.forDocument(document, project, true)

        if (markupModel !is MarkupModelEx) {
            return null
        }

        return ErrorLens(
            document,
            editor.inlayModel,
            markupModel,
            settings
        )
    }
}

class ErrorLens(
    private val document: Document,
    private val inlayModel: InlayModel,
    markupModel: MarkupModelEx,
    private val settings: ErrorLensSettings
) : Disposable {
    private val highlighters = mutableListOf<RangeHighlighter>()

    private val linesToReanalyze = Collections.synchronizedList<Int>(mutableListOf())
    private val alarm: Alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        markupModel.addMarkupModelListener(this, object : MarkupModelListener {
            override fun afterAdded(highlighter: RangeHighlighterEx) {
                val info = extractHighlightInfo(highlighter) ?: return

                if (highlightSeverities.contains(info.severity)) {
                    annotateFileError(highlighter)
                }
            }

            override fun beforeRemoved(highlighter: RangeHighlighterEx) {
                maybeRemoveFileErrorBecauseHighlighterWasRemoved(highlighter)
            }

            private fun extractHighlightInfo(highlighter: RangeHighlighterEx): HighlightInfo? {
                return highlighter.errorStripeTooltip as? HighlightInfo ?: return null
            }
        })

        document.addDocumentListener(object : DocumentListener {
            override fun beforeDocumentChange(event: DocumentEvent) {
                if (event.offset >= 0 && event.offset <= document.textLength) {
                    val line = document.getLineNumber(event.offset)
                    deleteLabelsAtLineAndScheduleReanalyzeLine(line)
                }
            }
        }, this)
    }

    private fun annotateFileError(highlighter: RangeHighlighter) {
        if (highlighter.startOffset >= 0 && highlighter.startOffset <= document.textLength) {
            highlighters.add(highlighter)
            val line = document.getLineNumber(highlighter.startOffset)
            refreshLabelAtLine(line)
        }
    }

    private fun maybeRemoveFileErrorBecauseHighlighterWasRemoved(highlighter: RangeHighlighter) {
        if (!highlighters.contains(highlighter))
            return

        highlighters.remove(highlighter)

        // Remove label associated with this highlighter and create new label at the same line
        inlayModel
            .getAfterLineEndElementsInRange(
                0,
                document.textLength,
                ErrorLabel::class.java
            )
            .filter { inlay -> inlay.renderer.associatedHighlighters.contains(highlighter) }
            .groupBy { inlay -> inlay.renderer.line }
            .forEach { (line, inlays) ->
                inlays.forEach { Disposer.dispose(it) }

                if (line < document.lineCount) {
                    refreshLabelAtLine(line)
                }
            }
    }

    private fun removeInlaysAtLine(line: Int) {
        val startOffset = document.getLineStartOffset(line)
        val endOffset = document.getLineEndOffset(line)

        inlayModel
            .getAfterLineEndElementsInRange(
                startOffset,
                endOffset,
                ErrorLabel::class.java
            )
            .forEach { inlay -> Disposer.dispose(inlay) }
    }

    private fun deleteLabelsAtLineAndScheduleReanalyzeLine(line: Int) {
        removeInlaysAtLine(line)

        synchronized(linesToReanalyze) {
            linesToReanalyze.add(line)
        }

        alarm.cancelAllRequests()
        alarm.addRequest({ reanalyzeLines() }, settings.reanalyzeDelayMs)
    }

    private fun reanalyzeLines() {
        val lines = synchronized(linesToReanalyze) {
            val copy = linesToReanalyze.toList()
            linesToReanalyze.clear()

            copy
        }

        lines.forEach { line ->
            if (line < 0 || line >= document.lineCount) {
                return
            }

            refreshLabelAtLine(line)
        }
    }

    private fun refreshLabelAtLine(line: Int) {
        removeInlaysAtLine(line)

        val relevantHighlighters = highlighters
            .filter { highlighter -> document.getLineNumber(highlighter.startOffset) == line && (highlighter.errorStripeTooltip as? HighlightInfo)?.description != null }
            .sortedBy { highlighter -> (highlighter.errorStripeTooltip as HighlightInfo).severity.myVal }
            .reversed()

        if (relevantHighlighters.isEmpty())
            return

        // Errors are sorted by severity, descending
        val highestSeverityHighlightInfo = relevantHighlighters[0].errorStripeTooltip as HighlightInfo

        val severity = highestSeverityHighlightInfo.severity
        val description = if (relevantHighlighters.size == 1) {
            highestSeverityHighlightInfo.description
        } else {
            val rest =
                if (relevantHighlighters.size == 2) " and 1 more error" else " and " + (relevantHighlighters.size - 1) + " more errors"
            highestSeverityHighlightInfo.description + rest
        }

        val errorLabel =
            ErrorLabel(JBLabel(description), determineColorForErrorSeverity(severity), line, relevantHighlighters)
        val endOffset = document.getLineEndOffset(line)
        val inlay = inlayModel.addAfterLineEndElement(endOffset, true, errorLabel)
        if (inlay == null) {
            logger.warn("Unable to create inlay with description \"$description\"")
            return
        }
    }

    private fun determineColorForErrorSeverity(severity: HighlightSeverity): Color =
        settings.getColorForSeverity(severity)
            ?: defaultColors[severity]
            ?: throw RuntimeException("Unable to determine color for " + severity.name)

    override fun dispose() {
    }
}

class ErrorLabel(
    private val label: JBLabel,
    private val textColor: Color,
    val line: Int,
    val associatedHighlighters: List<RangeHighlighter>,
) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int = label.preferredSize.width
    override fun calcHeightInPixels(inlay: Inlay<*>): Int = label.preferredSize.height

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val colorScheme = editor.colorsScheme

        g.font = colorScheme.getFont(EditorFontType.PLAIN)
        g.color = textColor
        g.drawString(label.text, targetRegion.x, targetRegion.y + editor.ascent)
    }
}

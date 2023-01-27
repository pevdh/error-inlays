package io.github.pevdh.error_lens

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
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
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle

private val logger = Logger.getInstance(ErrorLens::class.java)

class ErrorLensStartupActivity : StartupActivity {
    private val lenses: MutableMap<Editor, ErrorLens> = mutableMapOf()
    private val settings = ErrorLensSettings.instance

    override fun runActivity(project: Project) {
        val editorFactory = EditorFactory.getInstance()
        editorFactory
            .addEditorFactoryListener(object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    assertIsEdt()

                    attachToEditor(event.editor)
                }

                override fun editorReleased(event: EditorFactoryEvent) {
                    assertIsEdt()

                    val lens = lenses[event.editor] ?: return
                    Disposer.dispose(lens)
                    lenses.remove(event.editor)
                }
            }, project)

        editorFactory.allEditors.forEach { attachToEditor(it) }
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
    private val problems = Problems()

    private val linesToReanalyze = mutableSetOf<Int>()
    private val alarm: Alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        markupModel.addMarkupModelListener(this, object : MarkupModelListener {
            override fun afterAdded(highlighter: RangeHighlighterEx) {
                assertIsEdt()

                val problem = tryConvertToProblem(highlighter)
                    ?: return

                logger.info("Problem appeared: $problem")
                if (highlightSeverities.contains(problem.severity)) {
                    addProblemToInlineError(problem)
                }
            }

            override fun beforeRemoved(highlighter: RangeHighlighterEx) {
                assertIsEdt()

                val problem = tryConvertToProblem(highlighter)
                    ?: return

                logger.info("Problem disappeared: $problem")
                maybeRemoveInlayBecauseProblemDisappeared(problem)
            }

            private fun tryConvertToProblem(highlighter: RangeHighlighterEx): Problem? {
                if (highlighter.startOffset > document.textLength) {
                    // Problem is no longer visible
                    return null
                }

                val line = document.getLineNumber(highlighter.startOffset)
                val info = highlighter.errorStripeTooltip as? HighlightInfo ?: return null
                val severity = info.severity
                val description = info.description ?: return null

                return Problem(
                    highlighterId = highlighter.id,
                    line = line,
                    severity = severity,
                    description = description,
                )
            }
        })

        document.addDocumentListener(object : DocumentListener {
            override fun beforeDocumentChange(event: DocumentEvent) {
                assertIsEdt()

                val line = document.getLineNumber(event.offset)

                logger.info("Line changed: $line")
                removeInlineErrorAtLineAndScheduleReanalyzeLines(line)
            }
        }, this)
    }

    private fun addProblemToInlineError(problem: Problem) {
        problems.add(problem)

        refreshInlineErrorAtLine(problem.line)
    }

    private fun maybeRemoveInlayBecauseProblemDisappeared(problem: Problem) {
        val problemsAtLine = problems.atLine(problem.line)
        if (!problemsAtLine.contains(problem)) {
            return
        }

        problems.remove(problem)

        // Remove label associated with this highlighter and create new label at the same line
        inlayModel
            .getAfterLineEndElementsForLogicalLine(problem.line)
            .filter { inlay ->  InlineErrorRenderer::class.java.isInstance(inlay) }
            .forEach { inlay -> Disposer.dispose(inlay) }

        if (problem.line < document.lineCount) {
            refreshInlineErrorAtLine(problem.line)
        }
    }

    private fun removeInlineErrorAtLineAndScheduleReanalyzeLines(line: Int) {
        removeInlineErrorAtLine(line)

        linesToReanalyze.add(line)

        alarm.cancelAllRequests()
        alarm.addRequest({ reanalyzeLines() }, settings.reanalyzeDelayMs)
    }

    private fun removeInlineErrorAtLine(line: Int) {
        assertIsEdt()

        inlayModel
            .getAfterLineEndElementsForLogicalLine(line)
            .filter { inlay ->
                InlineErrorRenderer::class.java.isInstance(inlay.renderer)
            }
            .forEach { inlay ->
                Disposer.dispose(inlay)
            }
    }

    private fun reanalyzeLines() {
        assertIsEdt()

        val lines = linesToReanalyze.toList()
        linesToReanalyze.clear()

        lines.forEach { line ->
            if (line >= document.lineCount) {
                // Line has been deleted. Delete associated problems.
                problems.removeAtLine(line)
            } else {
                // Determine whether we need to display an inline error
                refreshInlineErrorAtLine(line)
            }
        }
    }

    private fun refreshInlineErrorAtLine(line: Int) {
        assertIsEdt()

        removeInlineErrorAtLine(line)

        // Highlighters are sorted by severity, descending
        val relevantProblems = problems.atLine(line)
            .sortedBy { problem -> problem.severity }
            .reversed()

        if (relevantProblems.isEmpty())
            return

        val highestSeverityProblem = relevantProblems[0]

        val severity = highestSeverityProblem.severity
        val description = if (relevantProblems.size == 1) {
            highestSeverityProblem.description
        } else {
            val rest =
                if (relevantProblems.size == 2) " and 1 more error" else " and " + (relevantProblems.size - 1) + " more errors"
            highestSeverityProblem.description + rest
        }

        val inlineErrorRenderer =
            InlineErrorRenderer(JBLabel(description), determineColorForErrorSeverity(severity))
        val endOffset = document.getLineEndOffset(line)

        val inlay = inlayModel.addAfterLineEndElement(
            endOffset,
            /* relatesToPrecedingText */ true,
            inlineErrorRenderer
        )

        if (inlay == null) {
            logger.warn("Unable to create inlay at line $line with description \"$description\"")
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

data class Problem(
    val highlighterId: Long,
    val line: Int,
    val severity: HighlightSeverity,
    val description: String,
)

class Problems {
    private val problemsByLine = mutableMapOf<Int, Set<Problem>>()
    private val problemsByHighlighterId = mutableMapOf<Long, Problem>()

    fun add(problem: Problem) {
        problemsByLine.merge(problem.line, mutableSetOf(problem)) { old, new -> old + new }
        problemsByHighlighterId[problem.highlighterId] = problem
    }

    fun remove(problem: Problem) {
        problemsByHighlighterId.remove(problem.highlighterId)

        val problemsAtLine = problemsByLine[problem.line] ?: return

        val removed = problemsAtLine.filter { p -> p != problem }.toSet()

        if (removed.isEmpty()) {
            problemsByLine.remove(problem.line)
        } else {
            problemsByLine[problem.line] = removed
        }
    }

    fun removeAtLine(line: Int) {
        val removed = problemsByLine.remove(line) ?: return

        removed.forEach { problem -> problemsByHighlighterId.remove(problem.highlighterId) }
    }

    fun atLine(line: Int) = problemsByLine.getOrDefault(line, setOf())
}

class InlineErrorRenderer(
    private val label: JBLabel,
    private val textColor: Color,
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

fun assertIsEdt() {
    ApplicationManager.getApplication().assertIsDispatchThread()
}

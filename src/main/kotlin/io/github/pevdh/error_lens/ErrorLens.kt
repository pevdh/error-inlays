package io.github.pevdh.error_lens

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.impl.event.MarkupModelListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle

private val LOGGER = Logger.getInstance(ErrorLens::class.java)

class ErrorLens(
    private val document: Document,
    private val inlayModel: InlayModel,
    private val settings: ErrorLensSettings,
) : Disposable {
    private val problems = Problems()

    private val linesToReanalyze = mutableSetOf<Int>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    companion object {
        fun tryCreateNewInstance(editor: Editor): ErrorLens? {
            val project = editor.project ?: return null
            val document = editor.document

            val markupModel = DocumentMarkupModel.forDocument(document, project, true)
            if (markupModel !is MarkupModelEx) {
                return null
            }

            val lens = ErrorLens(document, editor.inlayModel, ErrorLensSettings.instance)

            markupModel.addMarkupModelListener(lens, object : MarkupModelListener {
                override fun afterAdded(highlighter: RangeHighlighterEx) {
                    lens.notifyHighlighterAdded(highlighter)
                }

                override fun beforeRemoved(highlighter: RangeHighlighterEx) {
                    lens.notifyHighlighterRemoved(highlighter)
                }
            })

            document.addDocumentListener(object : DocumentListener {
                override fun beforeDocumentChange(event: DocumentEvent) {
                    lens.notifyLineChanged(document.getLineNumber(event.offset))
                }
            }, lens)

            markupModel.allHighlighters.forEach { highlighter ->
                lens.notifyHighlighterAdded(highlighter as RangeHighlighterEx)
            }

            return lens
        }
    }

    fun notifyHighlighterAdded(highlighter: RangeHighlighterEx) {
        val problem = tryConvertToProblem(highlighter) ?: return
        LOGGER.debug("Problem appeared: $problem")

        problems.add(problem)
        refreshInlineErrorAtLine(problem.line)
    }

    fun notifyHighlighterRemoved(highlighter: RangeHighlighterEx) {
        val problem = tryConvertToProblem(highlighter) ?: return
        LOGGER.debug("Problem disappeared: $problem")

        maybeRemoveInlineErrorBecauseProblemDisappeared(problem)
    }

    fun notifyLineChanged(line: Int) {
        LOGGER.debug("Line changed: $line")

        removeInlineErrorAtLine(line)
        linesToReanalyze.add(line)
        scheduleReanalyzeLines()
    }

    private fun tryConvertToProblem(highlighter: RangeHighlighterEx): Problem? {
        if (highlighter.startOffset >= document.textLength) {
            // Problem is no longer visible
            return null
        }

        val line = document.getLineNumber(highlighter.startOffset)
        val info = highlighter.errorStripeTooltip as? HighlightInfo ?: return null
        val severity = info.severity
        val description = info.description ?: return null

        if (!highlightSeverities.contains(severity)) {
            return null
        }

        return Problem(
            highlighterId = highlighter.id,
            line = line,
            severity = severity,
            description = description,
        )
    }

    private fun maybeRemoveInlineErrorBecauseProblemDisappeared(problem: Problem) {
        val problemsAtLine = problems.atLine(problem.line)
        if (!problemsAtLine.contains(problem)) {
            return
        }

        problems.remove(problem)

        // Remove label associated with this highlighter and create new label at the same line
        inlayModel.getAfterLineEndElementsForLogicalLine(problem.line)
            .filter { inlay -> InlineErrorRenderer::class.java.isInstance(inlay) }
            .forEach { inlay -> Disposer.dispose(inlay) }

        if (problem.line < document.lineCount) {
            refreshInlineErrorAtLine(problem.line)
        }
    }

    private fun scheduleReanalyzeLines() {
        alarm.cancelAllRequests()
        alarm.addRequest({ reanalyzeLines() }, settings.reanalyzeDelayMs)
    }

    private fun removeInlineErrorAtLine(line: Int) {
        inlayModel.getAfterLineEndElementsForLogicalLine(line)
            .filter { inlay ->
                InlineErrorRenderer::class.java.isInstance(inlay.renderer)
            }
            .forEach { inlay ->
                Disposer.dispose(inlay)
            }
    }

    private fun reanalyzeLines() {
        val lines = linesToReanalyze.toList()
        linesToReanalyze.clear()

        lines.forEach { line ->
            if (line >= document.lineCount) {
                // Line has been deleted. Delete associated problems.
                problems.removeProblemsAtLine(line)
            } else {
                // Determine whether we need to display an inline error
                refreshInlineErrorAtLine(line)
            }
        }
    }

    private fun refreshInlineErrorAtLine(line: Int) {
        removeInlineErrorAtLine(line)

        // Problems are sorted by severity, descending
        val relevantProblems = problems.atLine(line).sortedBy { problem -> problem.severity }.reversed()

        if (relevantProblems.isEmpty()) return

        val highestSeverityProblem = relevantProblems[0]

        val severity = highestSeverityProblem.severity
        val description = if (relevantProblems.size == 1) {
            highestSeverityProblem.description
        } else {
            val rest =
                if (relevantProblems.size == 2) " and 1 more error" else " and " + (relevantProblems.size - 1) + " more errors"
            highestSeverityProblem.description + rest
        }

        val inlineErrorRenderer = InlineErrorRenderer(JBLabel(description), determineColorForErrorSeverity(severity))
        val endOffset = document.getLineEndOffset(line)

        val inlay = inlayModel.addAfterLineEndElement(
            endOffset,
            /* relatesToPrecedingText */ true,
            inlineErrorRenderer,
        )

        if (inlay == null) {
            LOGGER.warn("Unable to create inlay at line $line with description \"$description\"")
            return
        }
    }

    private fun determineColorForErrorSeverity(severity: HighlightSeverity): Color =
        settings.getColorForSeverity(severity)
            ?: defaultColors[severity]
            ?: throw RuntimeException("Unable to determine color for " + severity.name)

    override fun dispose() {}
}

data class Problem(
    val highlighterId: Long,
    val line: Int,
    val severity: HighlightSeverity,
    val description: String,
)

class Problems {
    private val problemsByLine = mutableMapOf<Int, Set<Problem>>()

    fun add(problem: Problem) {
        problemsByLine.merge(problem.line, mutableSetOf(problem)) { old, new -> old + new }
    }

    fun remove(problem: Problem) {
        val problemsAtLine = problemsByLine[problem.line] ?: return

        val removed = problemsAtLine.filter { p -> p != problem }.toSet()

        if (removed.isEmpty()) {
            problemsByLine.remove(problem.line)
        } else {
            problemsByLine[problem.line] = removed
        }
    }

    fun removeProblemsAtLine(line: Int) {
        problemsByLine.remove(line)
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

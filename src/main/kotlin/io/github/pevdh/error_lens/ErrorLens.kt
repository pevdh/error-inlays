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
import com.intellij.util.CommonProcessors.CollectProcessor
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle
import java.lang.Integer.max

private val LOGGER = Logger.getInstance(ErrorLens::class.java)

class ErrorLens(
    private val document: Document,
    private val inlayModel: InlayModel,
    private val markupModel: MarkupModelEx,
    private val settings: ErrorLensSettings,
) : Disposable {
    private val linesScheduledToBeReanalyzed = mutableSetOf<Int>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    companion object {
        fun tryCreateNewInstance(editor: Editor): ErrorLens? {
            val project = editor.project ?: return null
            val document = editor.document

            val markupModel = DocumentMarkupModel.forDocument(document, project, true)
            if (markupModel !is MarkupModelEx) {
                return null
            }

            val lens = ErrorLens(document, editor.inlayModel, markupModel, ErrorLensSettings.instance)

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
                    val startOffset = event.offset
                    val endOffset = event.offset + max(event.oldLength, event.newLength)

                    val startLine = document.getLineNumber(startOffset)
                    val endLine =
                        if (endOffset < document.textLength) document.getLineNumber(endOffset) else document.lineCount - 1

                    for (line in startLine..endLine) {
                        lens.notifyLineChanged(line)
                    }
                }
            }, lens)

            markupModel.allHighlighters.forEach { highlighter ->
                lens.notifyHighlighterAdded(highlighter as RangeHighlighterEx)
            }

            return lens
        }
    }

    fun notifyHighlighterAdded(highlighter: RangeHighlighterEx) {
        val line = document.getLineNumber(highlighter.startOffset)

        LOGGER.debug("Highlighter added at line $line")
        if (linesScheduledToBeReanalyzed.contains(line)) {
            // Line is already scheduled to be re-analyzed
            // We do not want the inlay to pop up while to user is still typing
            return
        }

        refreshInlayAtLine(line)
    }

    fun notifyHighlighterRemoved(highlighter: RangeHighlighterEx) {
        if (highlighter.startOffset >= document.textLength) {
            // We do not need to refresh the inlay
            // because the line does not exist anymore
            return
        }

        val line = document.getLineNumber(highlighter.startOffset)

        LOGGER.debug("Highlighter removed at line $line")
        if (linesScheduledToBeReanalyzed.contains(line)) {
            // Line is already scheduled to be re-analyzed
            // We do not want the inlay to pop up while to user is still typing
            removeAnyErrorLensInlayAtLine(line)

            return
        }

        refreshInlayAtLine(line, ignoredHighlighterIds = setOf(highlighter.id))
    }

    fun notifyLineChanged(line: Int) {
        LOGGER.debug("Line $line changed")

        removeAnyErrorLensInlayAtLine(line)
        scheduleLineToBeReanalyzed(line)
    }

    private fun scheduleLineToBeReanalyzed(line: Int) {
        linesScheduledToBeReanalyzed.add(line)

        alarm.cancelAllRequests()
        alarm.addRequest({ reanalyzeLines() }, settings.reanalyzeDelayMs)
    }

    private fun reanalyzeLines() {
        linesScheduledToBeReanalyzed.forEach { line ->
            // Line could have been deleted
            if (line < document.lineCount) {
                refreshInlayAtLine(line)
            }
        }

        linesScheduledToBeReanalyzed.clear()
    }

    private fun removeAnyErrorLensInlayAtLine(line: Int) {
        LOGGER.debug("Removing all error lens inlays at line $line")
        inlayModel.getAfterLineEndElementsForLogicalLine(line)
            .filter { inlay -> InlineErrorRenderer::class.java.isInstance(inlay.renderer) }
            .forEach { inlay ->
                LOGGER.debug("Disposing existing inlay")
                Disposer.dispose(inlay)
            }
    }

    private fun refreshInlayAtLine(line: Int) {
        refreshInlayAtLine(line, setOf())
    }

    private fun refreshInlayAtLine(line: Int, ignoredHighlighterIds: Set<Long>) {
        LOGGER.debug("Attempting to refresh inlay at line $line")
        if (line >= document.lineCount) {
            return
        }

        removeAnyErrorLensInlayAtLine(line)

        // Problems are sorted by severity, descending
        val relevantProblems = findRelevantProblemsAtLine(line)
            .filter { problem -> !ignoredHighlighterIds.contains(problem.highlighterId) }
            .sortedBy { problem -> problem.severity }
            .reversed()

        LOGGER.debug("Found ${relevantProblems.size} relevant problems")

        if (relevantProblems.isEmpty()) return

        val severity = relevantProblems[0].severity
        val description = formatDescription(
            highestSeverityProblem = relevantProblems[0],
            remainingProblems = relevantProblems.drop(1),
        )

        LOGGER.debug("Creating inline error at line $line with description=$description and severity=$severity")

        val inlineErrorRenderer = InlineErrorRenderer(
            JBLabel(description),
            determineColorForErrorSeverity(severity),
        )
        val endOffset = document.getLineEndOffset(line)

        val newInlay = inlayModel.addAfterLineEndElement(
            endOffset,
            /* relatesToPrecedingText */ true,
            inlineErrorRenderer,
        )

        if (newInlay == null) {
            LOGGER.warn("Unable to create inlay at line $line with description \"$description\"")
        }
    }

    private fun findRelevantProblemsAtLine(line: Int): Set<Problem> {
        val lineStartOffset = document.getLineStartOffset(line)
        val lineEndOffset = document.getLineEndOffset(line)

        val highlighters = mutableListOf<RangeHighlighterEx>()
        val collector = CollectProcessor(highlighters)
        markupModel.processRangeHighlightersOverlappingWith(lineStartOffset, lineEndOffset, collector)

        return highlighters
            .filter { highlighter ->
                highlighter.startOffset in lineStartOffset..lineEndOffset
            }
            .mapNotNull { highlighter -> tryConvertToProblem(highlighter) }
            .toSet()
    }

    private fun tryConvertToProblem(highlighter: RangeHighlighterEx): Problem? {
        val info = highlighter.errorStripeTooltip as? HighlightInfo ?: return null
        val severity = info.severity
        val description = info.description ?: return null

        if (!highlightSeverities.contains(severity)) {
            return null
        }

        return Problem(
            highlighterId = highlighter.id,
            severity = severity,
            description = description,
        )
    }

    private fun formatDescription(highestSeverityProblem: Problem, remainingProblems: List<Problem>): String {
        return if (remainingProblems.isEmpty()) {
            highestSeverityProblem.description
        } else {
            val rest =
                if (remainingProblems.size == 1) " and 1 more error" else " and " + remainingProblems.size + " more errors"
            highestSeverityProblem.description + rest
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
    val severity: HighlightSeverity,
    val description: String,
)

class InlineErrorRenderer(
    private val label: JBLabel,
    private val textColor: Color,
) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int = label.preferredSize.width
    override fun calcHeightInPixels(inlay: Inlay<*>): Int = label.preferredSize.height

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val colorScheme = editor.colorsScheme
        val font = colorScheme.getFont(EditorFontType.PLAIN)
            .deriveFont(/* size */ colorScheme.editorFontSize2D * 0.95f)

        g.font = font
        g.color = textColor
        g.drawString(label.text, targetRegion.x, targetRegion.y + editor.ascent)
    }
}

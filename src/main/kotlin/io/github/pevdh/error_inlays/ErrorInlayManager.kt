package io.github.pevdh.error_inlays

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
import com.intellij.openapi.util.text.Strings
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.CommonProcessors.CollectProcessor
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle
import java.lang.Integer.max

private val logger = Logger.getInstance(ErrorInlayManager::class.java)

class ErrorInlayManager(
    private val document: Document,
    private val inlayModel: InlayModel,
    private val markupModel: MarkupModelEx,
    private val settings: ErrorInlaysSettings,
) : Disposable {
    private val linesScheduledToBeReanalyzed = mutableSetOf<Int>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    companion object {
        fun tryCreateNewInstance(editor: Editor): ErrorInlayManager? {
            val project = editor.project ?: return null
            val document = editor.document

            val markupModel = DocumentMarkupModel.forDocument(document, project, true)
            if (markupModel !is MarkupModelEx) {
                return null
            }

            val manager = ErrorInlayManager(document, editor.inlayModel, markupModel, ErrorInlaysSettings.instance)

            markupModel.addMarkupModelListener(manager, object : MarkupModelListener {
                override fun afterAdded(highlighter: RangeHighlighterEx) {
                    manager.notifyHighlighterAdded(highlighter)
                }

                override fun beforeRemoved(highlighter: RangeHighlighterEx) {
                    manager.notifyHighlighterRemoved(highlighter)
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
                        manager.notifyLineChanged(line)
                    }
                }
            }, manager)

            markupModel.allHighlighters.forEach { highlighter ->
                manager.notifyHighlighterAdded(highlighter as RangeHighlighterEx)
            }

            return manager
        }
    }

    fun notifyHighlighterAdded(highlighter: RangeHighlighterEx) {
        val line = document.getLineNumber(highlighter.startOffset)

        logger.debug("Highlighter added at line $line")
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

        logger.debug("Highlighter removed at line $line")
        if (linesScheduledToBeReanalyzed.contains(line)) {
            // Line is already scheduled to be re-analyzed
            // We do not want the inlay to pop up while to user is still typing
            removeAnyErrorInlayAtLine(line)

            return
        }

        refreshInlayAtLine(line, ignoredHighlighterIds = setOf(highlighter.id))
    }

    fun notifyLineChanged(line: Int) {
        logger.debug("Line $line changed")

        removeAnyErrorInlayAtLine(line)
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

    private fun removeAnyErrorInlayAtLine(line: Int) {
        logger.debug("Removing all error inlays at line $line")
        inlayModel.getAfterLineEndElementsForLogicalLine(line)
            .filter { inlay -> InlineErrorRenderer::class.java.isInstance(inlay.renderer) }
            .forEach { inlay ->
                logger.debug("Disposing existing inlay")
                Disposer.dispose(inlay)
            }
    }

    private fun refreshInlayAtLine(line: Int) {
        refreshInlayAtLine(line, setOf())
    }

    private fun refreshInlayAtLine(line: Int, ignoredHighlighterIds: Set<Long>) {
        logger.debug("Attempting to refresh inlay at line $line")
        if (line >= document.lineCount) {
            return
        }

        removeAnyErrorInlayAtLine(line)

        // Problems are sorted by severity, descending
        val relevantProblems = findRelevantProblemsAtLine(line)
            .filter { problem -> !ignoredHighlighterIds.contains(problem.highlighterId) }
            .sortedBy { problem -> problem.severity }
            .reversed()

        logger.debug("Found ${relevantProblems.size} relevant problems")

        if (relevantProblems.isEmpty()) return

        val severity = relevantProblems[0].severity
        val description = formatInlayDescription(
            highestSeverityProblem = relevantProblems[0],
            remainingProblems = relevantProblems.drop(1),
        )

        logger.debug("Creating inline error at line $line with description=$description and severity=$severity")

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
            logger.warn("Unable to create inlay at line $line with description \"$description\"")
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
            description = formatProblemDescription(description),
        )
    }

    private fun formatInlayDescription(highestSeverityProblem: Problem, remainingProblems: List<Problem>): String {
        return if (remainingProblems.isEmpty()) {
            highestSeverityProblem.description
        } else {
            highestSeverityProblem.description + " (and ${remainingProblems.size} more error(s))"
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

fun formatProblemDescription(rawDescription: String): String {
    val description = Strings.unescapeXmlEntities(rawDescription)

    if (description.startsWith("ESLint:")) {
        return description
            // Remove ESLint rule identifier at the end of the string.
            // It can still be viewed when hovering over the error.
            .replace(Regex("\\(.+\\)$"), "")
            .trim()
    }

    return Strings.unescapeXmlEntities(rawDescription)
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
        val font = colorScheme.getFont(EditorFontType.PLAIN)
            .deriveFont(/* size */ colorScheme.editorFontSize2D * 0.95f)

        g.font = font
        g.color = textColor
        g.drawString(label.text, targetRegion.x, targetRegion.y + editor.ascent)
    }
}

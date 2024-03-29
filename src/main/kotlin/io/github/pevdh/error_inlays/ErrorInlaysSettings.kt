package io.github.pevdh.error_inlays

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.Configurable
import com.intellij.ui.ColorPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.fields.IntegerField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.xmlb.XmlSerializerUtil
import java.awt.Color
import javax.swing.JComponent
import javax.swing.JPanel


val defaultColors = mapOf(
    HighlightSeverity.WEAK_WARNING to Color(0x756D56),
    HighlightSeverity.WARNING to Color(0xFFC31F),
    HighlightSeverity.ERROR to Color(0xFF6565),
)

val highlightSeverities = defaultColors.keys.toList()

class ErrorInlaysConfigurable : Configurable {
    private var component: ErrorInlaysSettingsComponent? = null
    private val settings get() = ErrorInlaysSettings.instance

    override fun getDisplayName(): String = "Error Inlays"

    override fun createComponent(): JComponent? {
        component = ErrorInlaysSettingsComponent()

        return component?.panel
    }

    override fun getPreferredFocusedComponent(): JComponent? {
        return component?.preferredFocusedComponent
    }

    override fun isModified(): Boolean {
        var modified = false
        component?.also { component ->
            modified = settings.reanalyzeDelayMs != component.delayMs

            highlightSeverities.forEach { severity ->
                modified =
                    modified || (component.getColorForHighlight(severity) != settings.getColorForSeverity(severity))
            }
        }

        return modified
    }

    override fun apply() {
        component?.also { component ->
            settings.reanalyzeDelayMs = component.delayMs

            highlightSeverities.forEach { severity ->
                val color = component.getColorForHighlight(severity)
                    ?: defaultColors[severity]
                    ?: throw RuntimeException("No default color for severity " + severity.name)

                settings.setColorForSeverity(severity, color)
            }
        }
    }

    override fun reset() {
        component?.also { component ->
            component.delayMs = settings.reanalyzeDelayMs

            highlightSeverities.forEach { severity ->
                val color = settings.getColorForSeverity(severity)
                    ?: defaultColors[severity]
                    ?: throw RuntimeException("No default color for " + severity.name)

                component.setHighlightColor(severity, color)
            }
        }
    }

    override fun disposeUIResources() {
        component = null
    }
}

class ErrorInlaysSettingsComponent {
    private val mainPanel: JPanel

    private val delayMsTextField: IntegerField = IntegerField("Delay in ms", 0, 5000)

    private val highlightColorPanels = mutableMapOf<HighlightSeverity, ColorPanel>()

    val preferredFocusedComponent get() = delayMsTextField
    val panel get() = mainPanel

    var delayMs
        get() = delayMsTextField.value
        set(v) {
            delayMsTextField.value = v
        }

    init {
        val builder = FormBuilder.createFormBuilder()

        delayMsTextField.isCanBeEmpty = false
        builder.addLabeledComponent(JBLabel("Delay before re-rendering error inlays"), delayMsTextField, 1, false)

        for (severity in highlightSeverities) {
            ColorPanel().also { panel ->
                highlightColorPanels[severity] = panel
                builder.addLabeledComponent(JBLabel(severity.displayCapitalizedName), panel)
            }
        }

        mainPanel = builder.addComponentFillVertically(JPanel(), 0)
            .panel
    }

    fun setHighlightColor(severity: HighlightSeverity, color: Color) {
        highlightColorPanels[severity]?.selectedColor = color
    }

    fun getColorForHighlight(severity: HighlightSeverity): Color? {
        return highlightColorPanels[severity]?.selectedColor
    }
}

@State(name = "io.github.pevdh.error_inlays.ErrorInlaysSettings", storages = [Storage("ErrorInlaysPluginSettings.xml")])
data class ErrorInlaysSettings(
    var reanalyzeDelayMs: Int,
    var severityColors: MutableMap<String, Int>,
) : PersistentStateComponent<ErrorInlaysSettings> {

    @Suppress("unused")
    constructor() : this(
        1000,
        mutableMapOf(),
    )

    companion object {
        val instance: ErrorInlaysSettings
            get() = ApplicationManager.getApplication().getService(ErrorInlaysSettings::class.java)
    }

    override fun getState(): ErrorInlaysSettings {
        return this
    }

    override fun loadState(state: ErrorInlaysSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    fun getColorForSeverity(severity: HighlightSeverity): Color? {
        return severityColors[severity.name]?.let { Color(it) }
    }

    fun setColorForSeverity(severity: HighlightSeverity, color: Color) {
        severityColors[severity.name] = color.rgb
    }
}
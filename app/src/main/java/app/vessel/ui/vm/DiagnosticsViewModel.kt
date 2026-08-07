package app.vessel.ui.vm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vessel.data.DeviceReport
import app.vessel.data.DeviceReportReader
import app.vessel.data.asText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticsUiState(
    val loading: Boolean = true,
    val report: DeviceReport? = null,
)

/**
 * Diagnostics.
 *
 * The read touches `/proc`, `/sys`, `getprop` and an EGL context, so it happens
 * once on entry off the main thread and is then held. Re-reading on every
 * recomposition would spawn a GL context per frame.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reader: DeviceReportReader,
) : ViewModel() {

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val report = reader.read()
            _state.update { DiagnosticsUiState(loading = false, report = report) }
        }
    }

    /**
     * The whole report to the clipboard, as text.
     *
     * This is the screen's reason to exist: the fastest useful thing anyone can
     * attach to a bug report is the exact device it happened on, in a form that
     * can be pasted rather than transcribed from a screenshot.
     */
    fun copyReport() {
        val report = _state.value.report ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Vessel device report", report.asText()))
    }
}

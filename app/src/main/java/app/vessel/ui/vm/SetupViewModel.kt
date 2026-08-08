package app.vessel.ui.vm

import androidx.lifecycle.ViewModel
import app.vessel.data.ComponentSetup
import app.vessel.data.SetupState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * First-run component setup, as the UI sees it.
 *
 * Almost nothing, and deliberately: the work is owned by a `@Singleton` in
 * `data/` rather than by this, because it outlives every Activity that watches it
 * — a rotation must not restart a 900 MB unpack, and neither must going into a
 * session and coming back. This view model is the seam that lets a composable
 * observe it without reaching into Hilt's singleton graph itself.
 *
 * [start] is called on composition rather than from a button. There is no button;
 * see [app.vessel.data.ComponentSetup].
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val setup: ComponentSetup,
) : ViewModel() {

    val state: StateFlow<SetupState> = setup.state

    /** Idempotent, and idempotent across Activity recreation. */
    fun start() = setup.start()
}

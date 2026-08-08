package app.vessel.ui.shell

import app.vessel.data.AppRegistryStore
import app.vessel.data.SessionShellHost
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The shell's two seams.
 *
 * One of them is real now. [AppRegistry] is bound to `data/AppRegistryStore`,
 * which persists to `shortcuts.json` through DataStore, so a program added to a
 * container survives a cold start. The claim this module used to carry — that
 * swapping an implementation would be one line and nothing in `ui/` would move —
 * held: no screen changed, because every screen already talked to the interface.
 *
 * [ShellHost] is real too now. `data/SessionShellHost` reads the guest's
 * top-level windows out of the vendored X server, raises and focuses one by id,
 * and starts a named executable inside a *running* prefix — the three pieces
 * item 2 of `out/ui-needs-from-core.md` asked for. `UnavailableShellHost` is
 * dead code.
 *
 * The module lives in `ui/` because the interface pass owns `ui/` and not `di/`,
 * and a module installs from anywhere on the compile path. It can fold into
 * `di/DataModule.kt` once the second binding is real too.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ShellModule {

    @Binds
    @Singleton
    abstract fun appRegistry(impl: AppRegistryStore): AppRegistry

    @Binds
    @Singleton
    abstract fun shellHost(impl: SessionShellHost): ShellHost
}

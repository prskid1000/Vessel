package app.vessel.ui.shell

import app.vessel.data.AppRegistryStore
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
 * [ShellHost] is still the stand-in. It reports `available = false` with a
 * sentence naming what is missing, and every surface prints that sentence rather
 * than drawing dead controls. Three separate pieces of work sit behind it — a
 * window list from the vendored X server, focus-by-id, and starting a named
 * executable inside a *running* prefix — and they are specified as item 2 of
 * `out/ui-needs-from-core.md`.
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
    abstract fun shellHost(impl: UnavailableShellHost): ShellHost
}

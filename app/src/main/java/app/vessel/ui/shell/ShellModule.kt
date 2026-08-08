package app.vessel.ui.shell

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The shell's two seams, bound to their stand-ins.
 *
 * **These are the two lines to change.** When `data/` gains an app registry and
 * the session runtime learns to start a named executable, this module points at
 * them and nothing else in `ui/` moves — every screen already talks to
 * [AppRegistry] and [ShellHost] rather than to a concrete class.
 *
 * It lives in `ui/` rather than in `di/` because this pass owns `ui/` and not
 * `di/`, and a module is installable from anywhere on the compile path. Fold it
 * into `di/DataModule.kt` when the real implementations land, if that reads
 * better there.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ShellModule {

    @Binds
    @Singleton
    abstract fun appRegistry(impl: InMemoryAppRegistry): AppRegistry

    @Binds
    @Singleton
    abstract fun shellHost(impl: UnavailableShellHost): ShellHost
}

package app.vessel.core

/**
 * What Wine itself is compiled as. Chosen when a container is created and not
 * changeable afterwards, which is why the picker explains rather than toggles.
 */
enum class ArchProfile(val label: String, val explanation: String) {
    UNIVERSAL(
        label = "Universal",
        explanation = "Wine, DXVK and vkd3d are native ARM64EC. Only the application's own " +
            "x86 code is translated, by FEX inside the process.",
    ),
    COMPATIBILITY(
        label = "Compatibility",
        explanation = "The whole Wine tree is x86-64 under Box64. Slower in principle, but a " +
            "different enough code path to rescue installers and anti-cheat that ARM64EC refuses.",
    ),
}

/** One container, as the Containers screen shows it. */
data class ContainerProfile(
    val id: String,
    val name: String,
    val archProfile: ArchProfile,
    val wineBuild: String,
    val driver: String,
    val d3dLayer: String,
    /** Epoch millis, or null when the container has never been launched. */
    val lastRun: Long?,
)

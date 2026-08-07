package app.vessel.core

/**
 * The `type` field of a `.wcp` `profile.json`.
 *
 * The set is the Winlator-family one, mirrored from `KNOWN_TYPES` in
 * `build/package_wcp.py`; a package carrying anything outside it is refused
 * rather than installed. [wire] is the exact string in the manifest, which is
 * not always the Kotlin name.
 */
enum class ComponentType(val wire: String, val label: String) {
    WINE("Wine", "Wine"),
    PROTON("Proton", "Proton"),
    BOX64("Box64", "Box64"),
    WOWBOX64("WOWBox64", "WOWBox64"),
    FEXCORE("FEXCore", "FEX"),
    DXVK("DXVK", "DXVK"),
    VKD3D("VKD3D", "vkd3d"),
    D8VK("D8VK", "D8VK"),
    TURNIP("Turnip", "Turnip"),
    TOOLS("Tools", "Tools"),
}

/**
 * One entry of `registry/contents.json`, as the Components screen shows it.
 *
 * The field names are the ones `build/gen_registry.py` writes, which are in
 * turn the `.wcp` `profile.json` keys. [installed] is the one field that is not
 * in the registry — it is this device's answer, not the registry's.
 *
 * TODO: the registry also carries `sha256`, `url` and the provenance triple
 *  (`target`, `sourceSha`, `cpuFlags`). They belong here once there is a
 *  downloader to verify against and a detail screen to show them on.
 */
data class ComponentPackage(
    val id: String,
    val type: ComponentType,
    val name: String,
    val versionName: String,
    val versionCode: Int,
    val sizeBytes: Long,
    val installed: Boolean,
)

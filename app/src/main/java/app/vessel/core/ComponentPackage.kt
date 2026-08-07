package app.vessel.core

/**
 * The `type` field of a `.wcp` `profile.json`.
 *
 * The set is the Winlator-family one; a package carrying anything outside it is
 * refused rather than installed. [wire] is the exact string in the manifest,
 * which is not always the Kotlin name.
 *
 * `Box64` and `WOWBox64` are deliberately not in the set. Vessel's Wine is built
 * `arm64ec,aarch64,i386`, so FEX covers every case — x64 through
 * `libarm64ecfex.dll` and 32-bit x86 through WoW64 — and a Box64 package would
 * have nothing to translate for. A `.wcp` of either type is refused, which is
 * the right answer rather than a silent install of dead weight.
 */
enum class ComponentType(val wire: String, val label: String) {
    WINE("Wine", "Wine"),
    PROTON("Proton", "Proton"),
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
 * TODO: the registry also carries `sha256` and `url`. They belong here once
 *  there is a downloader to verify against.
 */
data class ComponentPackage(
    val id: String,
    val type: ComponentType,
    val name: String,
    val versionName: String,
    val versionCode: Int,
    val sizeBytes: Long,
    val installed: Boolean,

    /**
     * The provenance triple, read from `vessel.provenance` in the `.wcp`
     * `profile.json` and copied into the registry verbatim by
     * `build/gen_registry.py`.
     *
     * These are on the row rather than behind a detail screen on purpose.
     * Vessel's whole claim is "this was compiled for your device", and a claim
     * that cannot be checked in the interface is just a marketing line: [target]
     * names the device profile (`build/targets/<target>.env`), [sourceSha] the
     * upstream commit it was built from, and [cpuFlags] the flags the compiler
     * actually got — which is the one of the three most likely to be wrong,
     * because `resolve_cpu_flags` falls back when a toolchain refuses `-mcpu`.
     */
    val target: String,
    val sourceSha: String,
    val cpuFlags: String,
)

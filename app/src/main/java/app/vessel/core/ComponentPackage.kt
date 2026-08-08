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

    /**
     * Desktop OpenGL as a native `opengl32.dll` (Mesa/Zink over Vulkan).
     *
     * Not a Winlator-family type — we added it, and `package_wcp.py` carries a
     * matching entry. It is deliberately not labelled DXVK: it replaces WGL
     * rather than Direct3D, and reusing an existing type to avoid inventing one
     * would make this screen lie about what is installed.
     *
     * It exists because our Wine builds with no GLX — there is no desktop libGL
     * in the Android sysroot — so without this, OpenGL applications have no
     * renderer at all. Wine's `gdi32` resolves the WGL entry points from
     * whatever `opengl32.dll` is loaded, which is what makes the substitution
     * work, exactly as DXVK substitutes `d3d11.dll`.
     */
    OPENGL("OpenGL", "OpenGL"),

    TOOLS("Tools", "Tools"),
}

/**
 * One entry of `registry/contents.json`, as the Components screen shows it.
 *
 * The field names are the ones `build/gen_registry.py` writes, which are in
 * turn the `.wcp` `profile.json` keys. [installed] is the one field that is not
 * in the registry — it is this device's answer, not the registry's.
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

    /**
     * Where the archive is, and what it must hash to.
     *
     * Both are the registry's, and both are null for a package described from
     * the store instead: an installed component has no download left to do, the
     * registry is not kept on the device, and `profile.json` carries neither
     * field. Null therefore means "not applicable here" rather than "missing" —
     * which is why [isDownloadable] is the predicate to ask, not `url != null`.
     *
     * They are one pair rather than two independent fields on purpose. A URL
     * with no digest is a download nothing can check, and a digest with no URL
     * is a check with nothing to run against; `build/gen_registry.py` already
     * refuses to publish a registry where the digest is missing, and
     * [ComponentRegistry] refuses to *read* an entry where either is. The
     * failure this closes is the plain one: before these fields existed nothing
     * verified that an installed package was the one the registry described,
     * because the only value that could have said so never reached the app.
     */
    val sha256: String? = null,
    val url: String? = null,
) {
    /**
     * True when this entry names both a place to fetch from and a digest to
     * check it against — the only state in which a download may be started.
     *
     * Written as one predicate so no caller can decide it a different way. The
     * digest is checked for shape as well as presence: a registry that carries
     * `"sha256": "TODO"` would otherwise pass a `!= null` test and be verified
     * against a string no file can ever hash to, which fails at the end of an
     * 88 MB download rather than before it starts.
     */
    val isDownloadable: Boolean
        get() = !url.isNullOrBlank() && Sha256.isWellFormed(sha256)
}

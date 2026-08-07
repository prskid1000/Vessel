package app.vessel.core

/**
 * What a PE executable is built for, read from `IMAGE_FILE_HEADER.Machine`.
 *
 * ARM64 and ARM64EC share machine `0xAA64` and are told apart by the load
 * config directory's `CHPEMetadataPointer`, so they are separate entries here
 * with the same value rather than one.
 *
 * The badge colour for each is in `ui/components/VArchBadge.kt`, so the palette
 * stays in one place — this layer holds no Compose types.
 */
enum class PeArchitecture(val label: String, val machine: Int) {
    ARM64("ARM64", 0xAA64),
    ARM64EC("ARM64EC", 0xAA64),
    X64("x64", 0x8664),
    X86("x86", 0x014C),
    UNKNOWN("unknown", 0x0000),
}

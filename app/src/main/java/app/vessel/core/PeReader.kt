package app.vessel.core

import java.io.File
import java.io.RandomAccessFile

/**
 * What a PE file was built for, read from its own header.
 *
 * [PeArchitecture] declares the enum and the machine words; this is the four
 * reads that answer the question for a real file. Pure — a function over a
 * [File], no Android, no Compose — so it is testable without a device and
 * callable from any layer.
 *
 * The format, in the order this reads it:
 *
 * ```
 * 0x00  "MZ"                       the DOS stub's magic
 * 0x3C  e_lfanew          uint32   file offset of the PE header
 * +0    "PE\0\0"                   the PE signature
 * +4    Machine           uint16   IMAGE_FILE_HEADER.Machine
 * ```
 *
 * ARM64 and ARM64EC share machine `0xAA64` and are told apart by the load config
 * directory's `CHPEMetadataPointer`, which is several more indirections through
 * the optional header and the data directories. This deliberately does not go
 * there: the two are the same statement on every surface in this product —
 * *runs without translation* — so the extra reads would buy a distinction
 * nothing shows. [PeArchitecture.ARM64] is returned for both.
 *
 * Every failure is [PeArchitecture.UNKNOWN], and that is a real answer rather
 * than a swallowed error: a file that is not a PE, a truncated download and a
 * file the app cannot open are all "we could not tell", which is what the
 * unread badge in the design says.
 */
object PeReader {

    /** `IMAGE_DOS_SIGNATURE`. */
    private const val MZ = 0x5A4D

    /** `IMAGE_NT_SIGNATURE`, little-endian as read. */
    private const val PE = 0x00004550

    /** Where `e_lfanew` sits in the DOS header. */
    private const val E_LFANEW_OFFSET = 0x3CL

    /** The signature plus the two bytes of `Machine` that follow it. */
    private const val PE_HEADER_BYTES = 6

    /**
     * The furthest into a file `e_lfanew` may point before this stops believing
     * it. A real PE puts its header within the first few hundred bytes; a huge
     * value is a corrupt or hostile file, and following it would be a seek to an
     * arbitrary offset of an arbitrary file.
     */
    private const val MAX_HEADER_OFFSET = 0x1000L

    /** The architecture of [file], or [PeArchitecture.UNKNOWN] if it cannot be read. */
    fun architectureOf(file: File): PeArchitecture {
        if (!file.isFile || file.length() < E_LFANEW_OFFSET + Int.SIZE_BYTES) {
            return PeArchitecture.UNKNOWN
        }
        return runCatching {
            RandomAccessFile(file, "r").use { handle ->
                if (handle.readLittleEndianShort() != MZ) return PeArchitecture.UNKNOWN
                handle.seek(E_LFANEW_OFFSET)
                val headerAt = handle.readLittleEndianInt().toLong()
                if (headerAt <= 0 || headerAt > MAX_HEADER_OFFSET ||
                    headerAt + PE_HEADER_BYTES > handle.length()
                ) {
                    return PeArchitecture.UNKNOWN
                }
                handle.seek(headerAt)
                if (handle.readLittleEndianInt() != PE) return PeArchitecture.UNKNOWN
                fromMachine(handle.readLittleEndianShort())
            }
        }.getOrDefault(PeArchitecture.UNKNOWN)
    }

    /**
     * The machine word, as an architecture.
     *
     * Exposed so a caller that already has the bytes — a future PE resource
     * reader, say — does not open the file twice, and so this is unit-testable
     * without a fixture on disk.
     */
    fun fromMachine(machine: Int): PeArchitecture = when (machine) {
        PeArchitecture.ARM64.machine -> PeArchitecture.ARM64
        PeArchitecture.X64.machine -> PeArchitecture.X64
        PeArchitecture.X86.machine -> PeArchitecture.X86
        else -> PeArchitecture.UNKNOWN
    }

    /**
     * PE is little-endian; `RandomAccessFile` is big-endian, because Java's IO is
     * network order throughout. Two reads and a shift rather than a `ByteBuffer`,
     * which would mean allocating one per file in a directory listing.
     */
    private fun RandomAccessFile.readLittleEndianShort(): Int {
        val low = read()
        val high = read()
        if (low < 0 || high < 0) return -1
        return (high shl 8) or low
    }

    private fun RandomAccessFile.readLittleEndianInt(): Int {
        val b0 = read()
        val b1 = read()
        val b2 = read()
        val b3 = read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) return -1
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }
}

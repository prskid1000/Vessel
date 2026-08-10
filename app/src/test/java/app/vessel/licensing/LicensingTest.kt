package app.vessel.licensing

import app.vessel.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The distribution obligations, as assertions.
 *
 * `docs/LICENSING.md` opens by saying licensing here is a correctness problem
 * rather than paperwork. This file is what makes that literally true: every
 * claim in that document that a machine can check is checked here, so a change
 * that quietly breaks one fails the build instead of being discovered by
 * somebody reading the repository after it is public.
 *
 * What it deliberately does **not** check is whether the vendored tree matches
 * upstream — that needs the network, and lives in `build/verify_vendored.py`.
 * The offline half is the discipline around it: that every marked file is listed
 * and every listed file is marked.
 */
class LicensingTest {

    // --- LGPL-2.1 section 6: relinkability -----------------------------------

    @Test
    fun `R8 keeps the vendored LGPL packages whole`() {
        val rules = RepoFiles.file("app/proguard-rules.pro").readText()
        assertTrue(
            "app/proguard-rules.pro must -keep com.winlator.** — obfuscating the vendored " +
                "X server away would defeat LGPL-2.1 section 6's relinking requirement, and " +
                "would also break libwinlator's JNI name resolution",
            rules.contains("-keep class com.winlator.** { *; }"),
        )
        assertTrue(rules.contains("-keepnames class com.winlator.**"))
    }

    @Test
    fun `the release build actually applies those rules`() {
        // A keep rule in a file nothing loads is not a keep rule. This is the
        // other half of the assertion above, and it is the half that would go
        // wrong silently: shrinking runs on release only, so no debug test and
        // no device session would ever notice.
        val gradle = RepoFiles.file("app/build.gradle.kts").readText()
        assertTrue(gradle.contains("isMinifyEnabled = true"))
        assertTrue(
            "the release build type must name proguard-rules.pro",
            gradle.contains("\"proguard-rules.pro\""),
        )
    }

    // --- LGPL-2.1 section 6: "You must supply a copy of this License" --------

    @Test
    fun `the APK carries the LGPL text, byte for byte`() {
        val root = RepoFiles.file("LICENSE-LGPL-2.1").readBytes()
        val shipped = RepoFiles.file("app/src/main/res/raw/license_lgpl_2_1.txt").readBytes()
        assertArrayEqualsNormalised(
            "res/raw/license_lgpl_2_1.txt has drifted from LICENSE-LGPL-2.1",
            root,
            shipped,
        )
    }

    @Test
    fun `resource shrinking cannot delete the licences or the fonts`() {
        val keep = RepoFiles.file("app/src/main/res/raw/keep.xml").readText()
        for (name in KEPT_RESOURCES) {
            assertTrue("res/raw/keep.xml does not name $name", keep.contains(name))
        }
    }

    @Test
    fun `the interface gives the notice section 6 asks for`() {
        // The half that a file in a zip cannot satisfy. Section 6: "You must
        // give prominent notice with each copy of the work that the Library is
        // used in it and that the Library and its use are covered by this
        // License." Asserted against the source of the screen rather than a
        // screenshot, because the obligation is that the words ship — and this
        // test is the thing that notices if somebody trims them for length.
        val screen = RepoFiles
            .file("app/src/main/java/app/vessel/ui/screens/LicencesScreen.kt")
            .readText()
        assertTrue(
            "the licence screen does not name the Winlator X server",
            screen.contains("Winlator X server"),
        )
        assertTrue(
            "the licence screen does not name the licence covering it",
            screen.contains("GNU Lesser General Public"),
        )

        // And it has to be reachable, or it is a screen nobody is given.
        val home = RepoFiles
            .file("app/src/main/java/app/vessel/ui/screens/HomeScreen.kt")
            .readText()
        // An affordance on the root screen, whatever its shape. This was a
        // full-width line of prose at the foot of home and is now an icon in
        // its toolbar; what section 6 requires is that a user of any copy can
        // reach the notice, not that it is a sentence they cannot dismiss.
        assertTrue(
            "home does not offer a way to the licences",
            home.contains("onOpenLicences") && home.contains("""VIcons.Info, "Licences""""),
        )
    }

    @Test
    fun `every licence the notice lists is really in the APK`() {
        // `Licences.entries` names an `R.raw` per component. A resource id
        // cannot be resolved in a JVM test, so this reads the names out of the
        // source and checks the files — which catches the case that matters: an
        // entry added to the list whose text was never put in `res/raw`, so the
        // row opens onto the "could not be read" state in a shipped build.
        val source = RepoFiles
            .file("app/src/main/java/app/vessel/ui/screens/Licences.kt")
            .readText()
        val named = Regex("""R\.raw\.(\w+)""").findAll(source).map { it.groupValues[1] }.toSet()
        assertTrue("Licences.kt names no licence texts at all", named.isNotEmpty())
        for (name in named) {
            val file = RepoFiles.file("app/src/main/res/raw/$name.txt")
            assertTrue("res/raw/$name.txt is named by Licences.kt and does not exist", file.isFile)
            assertTrue("res/raw/$name.txt is empty", file.length() > 0)
        }
    }

    @Test
    fun `the adrenotools BSD notice ships, matching its own source tree`() {
        // BSD-2-Clause's first condition: redistributions of source code must
        // retain the copyright notice. It was in `cpp/adrenotools/LICENSE` and
        // nowhere the APK could show it.
        val upstream = RepoFiles.file("app/src/main/cpp/adrenotools/LICENSE").readBytes()
        val shipped = RepoFiles.file("app/src/main/res/raw/license_bsd_adrenotools.txt").readBytes()
        assertArrayEqualsNormalised(
            "res/raw/license_bsd_adrenotools.txt has drifted from cpp/adrenotools/LICENSE",
            upstream,
            shipped,
        )
    }

    @Test
    fun `the SGSR notice ships, and says the licence SGSR is actually under`() {
        // BSD-3-Clause clause 1: redistributions of source code must retain the
        // copyright notice. The source in question is the fragment shader inside
        // SGSRMaterial, so the notice has to be in two places — beside the code
        // and in the APK — and both are asserted here.
        //
        // The SPDX line is checked by name because this was requested as
        // Apache-2.0 and is not: getting that wrong would have shipped the wrong
        // licence text for a component whose licence is the whole obligation.
        val shipped = RepoFiles.file("app/src/main/res/raw/license_bsd_sgsr.txt").readText()
        assertTrue(shipped.contains("SPDX-License-Identifier: BSD-3-Clause"))
        assertTrue(shipped.contains("Qualcomm Innovation Center, Inc."))
        assertTrue(
            "the shipped text is not the three-clause licence",
            shipped.contains("Neither the name of the copyright holder"),
        )

        val material = RepoFiles
            .file("app/src/main/java/com/winlator/renderer/material/SGSRMaterial.java")
            .readText()
        assertTrue(
            "SGSRMaterial does not carry the copyright notice its shader is under",
            material.contains("Qualcomm Innovation Center, Inc. All rights reserved."),
        )
        assertTrue(material.contains("SPDX-License-Identifier: BSD-3-Clause"))
    }

    @Test
    fun `the licence notice does not repeat the retracted libadrenotools claim`() {
        // LICENSE used to argue that "or later" was needed because
        // libadrenotools is LGPL-3.0. It is BSD-2-Clause; the retraction, and
        // the reasoning that still holds, are in docs/LICENSING.md. The notice
        // itself must not restate the false premise.
        val notice = RepoFiles.file("LICENSE").readText()
        assertFalse(
            "LICENSE still claims libadrenotools is LGPL-3.0",
            notice.contains("LGPL-3.0") || notice.contains("LGPL 3"),
        )
        assertTrue(notice.contains("SPDX-License-Identifier: LGPL-2.1-or-later"))
    }

    // --- The vendored tree's own record --------------------------------------

    @Test
    fun `every locally modified vendored file is listed, and every listing is real`() {
        val readme = RepoFiles.file("app/src/main/java/com/winlator/README.md")
        val listed = pathsInTable(readme.readText(), "### Every file that differs from upstream")
        assertTrue("the README's file table is missing or empty", listed.isNotEmpty())

        val marked = buildSet {
            for (tree in VENDORED_TREES) {
                RepoFiles.directory(tree).walkTopDown()
                    .filter { it.isFile && it.name != "README.md" }
                    .filter { it.readText().contains("VESSEL:") }
                    .forEach { add(it.relativeTo(RepoFiles.root).invariantPath()) }
            }
        }

        assertEquals(
            "the set of vendored files carrying a // VESSEL: marker must equal the set " +
                "listed in com/winlator/README.md — LGPL-2.1 section 6(a) wants the Library's " +
                "changes recorded, and an unlisted one is an unrecorded one",
            listed.sorted(),
            marked.sorted(),
        )
    }

    // --- SIL Open Font License 1.1 -------------------------------------------

    @Test
    fun `both font licences ship, with their own copyright lines`() {
        val inter = RepoFiles.file("app/src/main/res/raw/license_ofl_inter.txt").readText()
        assertTrue(inter.contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue(inter.contains("The Inter Project Authors"))

        val mono = RepoFiles.file("app/src/main/res/raw/license_ofl_jetbrains_mono.txt").readText()
        assertTrue(mono.contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue(mono.contains("The JetBrains Mono Project Authors"))
    }

    @Test
    fun `the file called Inter is Inter, and is variable`() {
        val font = TrueTypeFont(RepoFiles.file("app/src/main/res/font/inter_variable.ttf"))
        assertEquals("Inter Variable", font.family)
        assertTrue(font.copyright.orEmpty().contains("Inter Project Authors"))
        // DESIGN.md: "Both ship as bundled variable fonts so the product is
        // identical across devices." A static Inter renamed into place would
        // pass a digest check and fail this one.
        assertEquals(listOf("opsz", "wght"), font.axes.map { it.tag })
        val weight = font.axes.single { it.tag == "wght" }
        // The type scale uses 400 and 500. An axis that could not reach 500
        // would make every heading render at the default weight.
        assertTrue(weight.minimum <= 400f && weight.maximum >= 500f)
    }

    @Test
    fun `the file called JetBrains Mono is JetBrains Mono, is variable, and is monospaced`() {
        val font = TrueTypeFont(RepoFiles.file("app/src/main/res/font/jetbrains_mono_variable.ttf"))
        assertEquals("JetBrains Mono", font.family)
        assertTrue(font.copyright.orEmpty().contains("JetBrains Mono Project Authors"))
        assertEquals(listOf("wght"), font.axes.map { it.tag })
        val weight = font.axes.single { it.tag == "wght" }
        assertTrue(weight.minimum <= 400f && weight.maximum >= 500f)
        // The whole complaint in the TODO was that "mono does not read as
        // monospaced". PANOSE bProportion 9 is the font's own claim that it is.
        assertEquals(9, font.panoseProportion)
        assertNotNull(font.version)
    }

    // --- Trademarks ----------------------------------------------------------

    @Test
    fun `no trademark appears in the product name`() {
        // docs/LICENSING.md: avoid these marks in the product name, icon or
        // store listing. Descriptive use inside the interface — "a Windows
        // program" — is nominative and stays.
        val properties = RepoFiles.file("gradle.properties").readText()
        val name = Regex("""^PRODUCT_NAME=(.*)$""", RegexOption.MULTILINE)
            .find(properties)?.groupValues?.get(1)?.trim()
        assertEquals("Vessel", name)
        for (mark in TRADEMARKS) {
            assertFalse(name.orEmpty().contains(mark, ignoreCase = true))
        }
    }

    // --- helpers -------------------------------------------------------------

    /** Backticked paths in the first Markdown table under [heading]. */
    private fun pathsInTable(markdown: String, heading: String): List<String> {
        val start = markdown.indexOf(heading)
        require(start >= 0) { "no '$heading' section in the README" }
        val rest = markdown.substring(start + heading.length)
        val end = rest.indexOf("\n## ").let { if (it < 0) rest.length else it }
        return Regex("""^\|\s*`([^`]+)`\s*\|""", RegexOption.MULTILINE)
            .findAll(rest.substring(0, end))
            .map { it.groupValues[1] }
            .toList()
    }

    private fun java.io.File.invariantPath(): String = path.replace('\\', '/')

    /**
     * Compares with line endings normalised.
     *
     * The two files are the same text; whether a checkout gave one of them CRLF
     * is a property of the machine, not of the licence. `.gitattributes` forces
     * LF for both, and this stays tolerant so a failure here means the *text*
     * changed, which is the thing worth failing over.
     */
    private fun assertArrayEqualsNormalised(message: String, expected: ByteArray, actual: ByteArray) {
        assertEquals(
            message,
            String(expected, Charsets.UTF_8).replace("\r\n", "\n"),
            String(actual, Charsets.UTF_8).replace("\r\n", "\n"),
        )
    }

    private companion object {
        val VENDORED_TREES = listOf(
            "app/src/main/java/com/winlator",
            "app/src/main/cpp/winlator",
        )

        val KEPT_RESOURCES = listOf(
            "@raw/license_lgpl_2_1",
            "@raw/license_ofl_inter",
            "@raw/license_ofl_jetbrains_mono",
            "@raw/license_bsd_adrenotools",
            "@raw/license_bsd_sgsr",
            "@font/inter_variable",
            "@font/jetbrains_mono_variable",
        )

        val TRADEMARKS = listOf(
            "Windows", "DirectX", "Snapdragon", "Adreno", "Motorola", "Qualcomm",
        )
    }
}

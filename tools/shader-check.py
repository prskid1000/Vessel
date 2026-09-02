"""Compile every ShaderMaterial's GLSL, which the Java build cannot.

WHY THIS EXISTS. A ShaderMaterial holds its shader as a String.join over quoted
literals with // comments interleaved. To javac that is one opaque string, so a
dropped declaration, a misspelled identifier or an unbalanced brace compiles
perfectly and fails on the device, inside a session, as a black screen or a
silently unbound program. `assembleSideloadDebug` is a twenty-minute round trip
to find out.

It is not hypothetical. Editing the consistency block on 2026-09-01 dropped the
line declaring `occ` while leaving two uses of it; the Java compiled, the unit
suite passed, and the fault was one install away from the phone. ee16028
records an earlier one in the same file -- "a sampler a shader does not declare
is silent; reading one it does not declare is not".

WHAT IT DOES. Pulls the literals back out of each material, joins them the way
the material does, and hands the result to glslangValidator, which type-checks
GLSL without needing a GL context or a device.

    python tools/shader-check.py                    # every material
    python tools/shader-check.py InterpolateMaterial

Exit status is the number of materials that failed, so it drops into a hook or
a CI step unchanged.

THE VALIDATOR. Ships with the Android SDK emulator; the path is searched below
and can be overridden with GLSLANG. There is no version pinned and none needed:
this is asked for a yes or no about syntax and identifiers, not for a binary.
If it cannot be found the script says so and exits 0 rather than failing a
build for a missing optional tool -- a check that cannot run is not a failure,
but it must not be a silent pass either, so it is loud about it.

WHAT IT CANNOT ESTABLISH. The device's compiler is Adreno's, not this one.
Precision qualifiers, loop-unrolling limits and vendor extensions are all
outside what this sees, and a shader that passes here can still be rejected
there. It answers "is this GLSL" and nothing about whether the picture is
right.
"""
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MATERIALS = ROOT / "app/src/main/java/com/winlator/renderer/material"

LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')

CANDIDATES = [
    os.environ.get("GLSLANG"),
    os.path.expanduser(
        "~/AppData/Local/Android/Sdk/emulator/lib64/vulkan/glslangValidator.exe"),
    "/usr/bin/glslangValidator",
    "glslangValidator",
]


def validator():
    for candidate in CANDIDATES:
        if not candidate:
            continue
        path = Path(os.path.expanduser(candidate))
        if path.exists():
            return str(path)
        found = subprocess.run(["which", candidate], capture_output=True, text=True)
        if found.returncode == 0:
            return found.stdout.strip()
    return None


# What ShaderMaterial.compileShaders splices into every vertex shader, just
# before its main(). Mirrored here rather than ignored: without it every vertex
# shader that transforms a position fails on an undefined function, which is a
# fault in this script and not in the shader.
APPLY_XFORM = (
    "vec2 applyXForm(vec2 p, float xform[6]) {\n"
    "return vec2(xform[0] * p.x + xform[2] * p.y + xform[4],"
    " xform[1] * p.x + xform[3] * p.y + xform[5]);\n"
    "}\n"
)

# A join argument that is not a quoted literal -- `versionDirective()`, a
# constant holding a vendor shader body -- means the real source cannot be
# rebuilt from the file alone.
JAVA_REFERENCE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
JOIN_NOISE = {"return", "String", "join", "n"}


# SGSRMaterial composes its shaders from two methods and two constants. The
# methods are mirrored here at their defaults; the constants are String.join
# blocks in the same file and are read out of it.
KNOWN_METHODS = {
    "versionDirective": "#version 310 es",
    "tuningDefines": "#define OperationMode 1\n#define UseEdgeDirection\n"
                     "#define EdgeThreshold 0.03137255\n#define EdgeSharpness 2.0\n"
                     "#define MaxDelta 0.09019608",
}


def resolve(text, word):
    """The GLSL a Java identifier stands for, or None."""
    if word in KNOWN_METHODS:
        return KNOWN_METHODS[word]
    marker = "String %s = String.join(" % word
    if marker not in text:
        return None
    block = text[text.index(marker):]
    block = block[:block.index("\n    );")]
    literals = []
    for line in block.split("\n")[1:]:
        stripped = line.strip()
        if stripped.startswith("//"):
            continue
        for literal in LITERAL.findall(stripped):
            literals.append(literal.encode().decode("unicode_escape"))
    return "\n".join(literals)


def shaders(java):
    """Every (kind, source, skip_reason) this file builds."""
    text = java.read_text(encoding="utf-8")
    out = []
    for method in ("getVertexShader", "getFragmentShader"):
        marker = "protected String %s()" % method
        if marker not in text:
            continue
        body = text[text.index(marker):]
        # The method ends at the first closing brace in column four.
        body = body[:body.index("\n    }")]
        lines = []
        unresolved = set()
        for line in body.split("\n"):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith(marker):
                continue
            literals = LITERAL.findall(stripped)
            # Whatever is left once the literals are gone is Java. A join
            # argument that is one of this file's own String.join constants,
            # or one of the two methods SGSRMaterial composes its header from,
            # is resolved in place; anything else is genuinely not
            # reconstructible from the file alone.
            residue = LITERAL.sub("", stripped)
            words = [w for w in JAVA_REFERENCE.findall(residue) if w not in JOIN_NOISE]
            if words and not literals:
                for word in words:
                    value = resolve(text, word)
                    if value is None:
                        unresolved.add(word)
                    else:
                        lines.append(value)
                continue
            for literal in literals:
                lines.append(literal.encode().decode("unicode_escape"))

        source = "\n".join(lines).strip()
        # A material that inherits its shader contributes no literals.
        if not source:
            continue
        kind = method.replace("get", "").replace("Shader", "")
        if unresolved:
            out.append((kind, source,
                        "composed from Java (%s)" % ", ".join(sorted(unresolved))))
            continue
        if kind == "Vertex" and "void main() {" in source:
            head, tail = source.split("void main() {", 1)
            source = head + APPLY_XFORM + "void main() {" + tail
        out.append((kind, source, None))
    return out


def main():
    glslang = validator()
    if not glslang:
        print("shader-check: glslangValidator not found -- NOTHING WAS CHECKED.")
        print("  set GLSLANG=/path/to/glslangValidator, or install the Android")
        print("  SDK emulator package which ships one.")
        return 0

    wanted = sys.argv[1:]
    failures = 0
    skipped = 0
    scratch = ROOT / "build" / "shader-check"
    scratch.mkdir(parents=True, exist_ok=True)

    for java in sorted(MATERIALS.glob("*.java")):
        if wanted and not any(w in java.name for w in wanted):
            continue
        for kind, source, skip in shaders(java):
            stage = "vert" if kind == "Vertex" else "frag"
            if skip:
                skipped += 1
                print("  skip %-28s %s -- %s" % (java.stem, kind.lower(), skip))
                continue
            path = scratch / ("%s.%s" % (java.stem, stage))
            path.write_text(source + "\n", encoding="utf-8")
            done = subprocess.run([glslang, "-S", stage, str(path)],
                                  capture_output=True, text=True)
            if done.returncode == 0:
                print("  ok   %-28s %s" % (java.stem, kind.lower()))
            else:
                failures += 1
                print("  FAIL %-28s %s" % (java.stem, kind.lower()))
                for line in (done.stdout + done.stderr).splitlines():
                    if line.strip() and not line.strip().endswith(stage):
                        print("       " + line)

    print("\n%d shader%s failed, %d not reconstructible from the file alone"
          % (failures, "" if failures == 1 else "s", skipped))
    return failures


if __name__ == "__main__":
    sys.exit(main())

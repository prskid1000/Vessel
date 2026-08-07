# Vendored LGPL-2.1 code: the Winlator X server. Two separate reasons to keep
# it whole, either of which alone would be enough.
#
# 1. Correctness. libwinlator resolves Java members by name at runtime —
#    GPUImage.setStride, XConnectorEpoll.handleNewConnection and friends via
#    GetMethodID, and every JNI entry point through its
#    Java_com_winlator_<class>_<method> symbol. Renaming any of those compiles,
#    ships, and then fails on the device the first time a window is created.
#    @Keep covers the reflective ones today; this covers the ones nobody
#    remembered to annotate.
#
# 2. Licensing. LGPL-2.1 section 6 requires that a user be able to relink the
#    app against their own build of the LGPL part. Obfuscating it away would be
#    a licensing regression, not just a debugging annoyance. See
#    docs/LICENSING.md.
-keep class com.winlator.** { *; }
-keepnames class com.winlator.**

# The JNI entry points in libcalc.so are named
# Java_io_github_kmarzouq_calcplus_NativeBridge_*, so this class and its native
# method names must survive R8 unrenamed.
-keepclasseswithmembernames,includedescriptorclasses class io.github.kmarzouq.calcplus.NativeBridge {
    native <methods>;
}

# Manifest components are kept automatically; nothing else to add.
-dontwarn org.jetbrains.annotations.**

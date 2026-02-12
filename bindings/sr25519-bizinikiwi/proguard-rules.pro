# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep BizinikiwSr25519 object
-keep class io.novafoundation.nova.sr25519.BizinikiwSr25519 { *; }

# ============================================================
# Pezkuwi Wallet ProGuard Rules
# ============================================================

# Keep line numbers for debugging crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============================================================
# Kotlin
# ============================================================
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ============================================================
# Retrofit & OkHttp (Strict rules for generic type preservation)
# ============================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Keep Retrofit library
-keep class retrofit2.** { *; }
-keepclassmembers class retrofit2.** { *; }

# Essential attributes for reflection
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleParameterAnnotations
-keepattributes AnnotationDefault

# Keep ALL interfaces with Retrofit annotations - NO allowshrinking/allowobfuscation
-keep interface * {
    @retrofit2.http.* <methods>;
}

# Keep the method signatures including generic types
-keepclasseswithmembers interface * {
    @retrofit2.http.* <methods>;
}

# ============================================================
# Gson
# ============================================================
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ============================================================
# BouncyCastle Crypto
# ============================================================
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ============================================================
# Native JNI Bindings (Rust)
# ============================================================
# SR25519 signing - keep the native methods and the class
-keep class io.novafoundation.nova.sr25519.BizinikiwSr25519 { *; }
-keep class io.novafoundation.nova.** { native <methods>; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep all JNI related classes
-keep class io.parity.** { *; }

# Runtime signers
-keep class io.novafoundation.nova.runtime.extrinsic.signer.** { *; }

# ============================================================
# Substrate SDK
# ============================================================
-keep class jp.co.soramitsu.** { *; }
-dontwarn jp.co.soramitsu.**

# Nova Substrate SDK (io.novasama)
-keep class io.novasama.substrate_sdk_android.** { *; }
-keepclassmembers class io.novasama.substrate_sdk_android.** { *; }
-dontwarn io.novasama.substrate_sdk_android.**

# XXHash library (used by Substrate SDK hashing)
-keep class net.jpountz.** { *; }
-keepclassmembers class net.jpountz.** { *; }
-dontwarn net.jpountz.**

# Keep Schema objects and their delegated properties
-keep class * extends io.novasama.substrate_sdk_android.scale.Schema { *; }
-keepclassmembers class * extends io.novasama.substrate_sdk_android.scale.Schema {
    <fields>;
    <methods>;
}

# ============================================================
# Secrets & Crypto Classes
# ============================================================
-keep class io.novafoundation.nova.common.data.secrets.** { *; }
-keepclassmembers class io.novafoundation.nova.common.data.secrets.** { *; }
-keep class io.novafoundation.nova.feature_account_impl.data.secrets.** { *; }
-keep class io.novafoundation.nova.feature_account_impl.data.repository.datasource.** { *; }
-keep class io.novafoundation.nova.feature_account_impl.data.repository.addAccount.** { *; }

# ============================================================
# Firebase
# ============================================================
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ============================================================
# Branch.io Deep Linking
# ============================================================
-keep class io.branch.** { *; }
-dontwarn io.branch.**

# ============================================================
# Web3j (Ethereum)
# ============================================================
-keep class org.web3j.** { *; }
-dontwarn org.web3j.**

# ============================================================
# SQLCipher
# ============================================================
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# ============================================================
# Room Database
# ============================================================
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ============================================================
# Data Classes & Models (Keep for serialization)
# ============================================================
-keep class io.novafoundation.nova.**.model.** { *; }
-keep class io.novafoundation.nova.**.response.** { *; }
-keep class io.novafoundation.nova.**.request.** { *; }
-keep class io.novafoundation.nova.**.dto.** { *; }
-keep class io.novafoundation.nova.**.*Remote { *; }
-keep class io.novafoundation.nova.**.*Remote$* { *; }

# ============================================================
# Parcelable
# ============================================================
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ============================================================
# Enums
# ============================================================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================================
# Serializable
# ============================================================
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ============================================================
# Ledger USB/Bluetooth
# ============================================================
-keep class io.novafoundation.nova.feature_ledger_impl.** { *; }

# ============================================================
# WalletConnect
# ============================================================
-keep class com.walletconnect.** { *; }
-keepclassmembers class com.walletconnect.** { *; }
-dontwarn com.walletconnect.**

# ============================================================
# Google API Client (Google Drive)
# ============================================================
-keep class com.google.api.** { *; }
-keepclassmembers class com.google.api.** { *; }
-keep class com.google.api.client.** { *; }
-keepclassmembers class com.google.api.client.** { *; }
-keep class com.google.api.services.** { *; }
-keepclassmembers class com.google.api.services.** { *; }
-dontwarn com.google.api.**

# ============================================================
# Navigation Component
# ============================================================
-keep class * extends androidx.navigation.Navigator { *; }
-keep @androidx.navigation.Navigator.Name class * { *; }
-keepnames class * extends androidx.navigation.Navigator
-keepattributes *Annotation*
-keep class androidx.navigation.** { *; }
-keep class * implements androidx.navigation.NavArgs { *; }
-keep class androidx.navigation.fragment.** { *; }
-keep class io.novafoundation.nova.**.navigation.** { *; }
-keep class * extends androidx.navigation.NavDestination { *; }

# Keep all Nova foundation classes (prevent aggressive obfuscation)
-keep class io.novafoundation.nova.** { *; }
-keepnames class io.novafoundation.nova.**

# ============================================================
# Optimization settings
# ============================================================
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*,!method/inlining/*
-optimizationpasses 5
-allowaccessmodification

# Don't optimize or obfuscate Retrofit interfaces - critical for type reflection
-keepnames,includedescriptorclasses interface * {
    @retrofit2.http.* <methods>;
}

# ============================================================
# Don't warn about missing classes that we don't use
# ============================================================
-dontwarn org.conscrypt.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn org.w3c.dom.traversal.**
-dontwarn org.apache.xerces.**
-dontwarn org.apache.xml.**
-dontwarn org.apache.xalan.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.http.**

# ByteBuddy (test dependency)
-dontwarn net.bytebuddy.**
-dontwarn com.sun.jna.**
-dontwarn edu.umd.cs.findbugs.annotations.**

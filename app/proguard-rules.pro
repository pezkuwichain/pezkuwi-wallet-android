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
# Retrofit & OkHttp
# ============================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions
-keepclasseswithmembers class * {
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
# SR25519 signing
-keep class io.novafoundation.nova.** { native <methods>; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep all JNI related classes
-keep class io.parity.** { *; }

# ============================================================
# Substrate SDK
# ============================================================
-keep class jp.co.soramitsu.** { *; }
-dontwarn jp.co.soramitsu.**

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
-dontwarn com.walletconnect.**

# ============================================================
# Optimization settings
# ============================================================
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification

# ============================================================
# Don't warn about missing classes that we don't use
# ============================================================
-dontwarn org.conscrypt.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**

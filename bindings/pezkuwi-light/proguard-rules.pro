# JNI resolves these methods by name.
-keep class io.novafoundation.nova.lightclient.PezkuwiLight {
    native <methods>;
}
# Native code calls HostResolver.resolve by name.
-keep interface io.novafoundation.nova.lightclient.HostResolver {
    java.lang.String[] resolve(java.lang.String);
}
-keepclassmembers class * implements io.novafoundation.nova.lightclient.HostResolver {
    java.lang.String[] resolve(java.lang.String);
}

pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // کتابخانه‌ی پرداخت بازار (Poolakey) از JitPack می‌آید؛ فقط همین گروه از آن دریافت می‌شود
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.cafebazaar.*") }
        }
    }
}
rootProject.name = "SmsForwarder"
include(":app")

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// اطلاعات امضای نسخه‌ی انتشار: از فایل keystore.properties (کنار build.gradle.kts ریشه)
// یا از متغیرهای محیطی (برای GitHub Actions) خوانده می‌شود. هیچ‌کدام در مخزن ذخیره نمی‌شوند.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signing(key: String, env: String): String? =
    keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signing("storeFile", "KEYSTORE_PATH")
val releaseStorePassword = signing("storePassword", "KEYSTORE_PASSWORD")
val releaseKeyAlias = signing("keyAlias", "KEY_ALIAS")
val releaseKeyPassword = signing("keyPassword", "KEY_PASSWORD")
// کلید عمومی RSA پرداخت درون‌برنامه‌ای (پیشخان بازار ← پرداخت درون‌برنامه‌ای) و شناسه‌ی محصول اشتراک
val bazaarRsaKey = (signing("bazaarRsaKey", "BAZAAR_RSA_KEY") ?: "").filterNot { it.isWhitespace() || it == '"' }
// دو طرح اشتراک در پیشخان بازار (بدون دوره‌ی آزمایشی): ماهانه ۴۰٬۰۰۰ و سالانه ۲۰۰٬۰۰۰ تومان
val skuMonthly = signing("skuMonthly", "SKU_MONTHLY") ?: "sms_monthly"
val skuYearly = signing("skuYearly", "SKU_YEARLY") ?: "sms_yearly"
val canSignRelease = releaseStoreFile != null && releaseStorePassword != null &&
        releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "ir.smsforward"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.smsforward"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "3.0.0"

        // آدرس فایل update.json برای بررسی نسخه‌ی جدید (فقط HTTPS).
        // YOUR_ را با آدرس واقعی خودتان عوض کنید؛ تا آن موقع بررسی بروزرسانی خاموش است.
        // نمونه: https://raw.githubusercontent.com/YOUR_USER/YOUR_REPO/main/update.json
        buildConfigField(
            "String", "UPDATE_URL",
            "\"https://raw.githubusercontent.com/YOUR_USER/YOUR_REPO/main/update.json\""
        )
        buildConfigField("String", "BAZAAR_RSA_KEY", "\"$bazaarRsaKey\"")
        buildConfigField("String", "SKU_MONTHLY", "\"$skuMonthly\"")
        buildConfigField("String", "SKU_YEARLY", "\"$skuYearly\"")
        // سازگاری با کدهای قدیمی
        buildConfigField("String", "SUBSCRIPTION_SKU", "\"$skuYearly\"")
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    bundle {
        language { enableSplit = false }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // پرداخت درون‌برنامه‌ای کافه‌بازار
    implementation("com.github.cafebazaar.Poolakey:poolakey:2.2.0")
}

// ساخت نسخه‌ی انتشار بدون کلید RSA بازار ممنوع است (نسخه‌ای که نتواند پرداخت را بررسی کند منتشر نشود)
gradle.taskGraph.whenReady {
    val releaseBuild = allTasks.any {
        (it.name.startsWith("assemble") || it.name.startsWith("bundle")) &&
            it.name.endsWith("Release", ignoreCase = true)
    }
    if (releaseBuild && bazaarRsaKey.isBlank()) {
        throw GradleException(
            "BAZAAR_RSA_KEY خالی است. کلید RSA را از پیشخان بازار بردارید و در keystore.properties " +
                "(bazaarRsaKey=...) یا متغیر محیطی/Secret با نام BAZAAR_RSA_KEY بگذارید."
        )
    }
}

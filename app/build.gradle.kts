plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.noor.blocker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.noor.blocker"
        minSdk = 24
        targetSdk = 34
        versionCode = 28
        versionName = "16.0"
    }

    // توقيعٌ ثابت بملفٍّ نملك مساره.
    //
    // مفتاح التصحيح الافتراضي يُولَّد من جديد على كل خادم بناء،
    // وأندرويد يرفض تحديث تطبيقٍ بمفتاحٍ مختلف — فكان التثبيت
    // يفشل ويبقى الهاتف على نسخةٍ قديمة.
    val noorKey = file("noor-signing.jks")
    signingConfigs {
        if (noorKey.exists()) {
            create("noor") {
                storeFile = noorKey
                storePassword = "noorsign"
                keyAlias = "noor"
                keyPassword = "noorsign"
            }
        }
    }

    buildTypes {
        val signer = if (noorKey.exists()) signingConfigs.getByName("noor")
                     else signingConfigs.getByName("debug")
        debug { signingConfig = signer }
        release {
            isMinifyEnabled = false
            signingConfig = signer
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// module.prop is the single source of truth for the version: LSPosed shows it, the APK copies it,
// and CI publishes every new version as the GitHub release "v<version>".
val moduleProp = Properties().apply {
    load(providers.fileContents(layout.projectDirectory.file("src/main/resources/META-INF/xposed/module.prop")).asText.get().reader())
}

android {
    namespace = "com.example.nolockqs"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.nolockqs"
        minSdk = 35
        targetSdk = 37
        versionCode = moduleProp.getProperty("versionCode").trim().toInt()
        versionName = moduleProp.getProperty("version").trim()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // The release key comes from the environment (CI secrets, see README > Releases).
        providers.environmentVariable("NOLOCKQS_KEYSTORE_FILE").orNull?.let { keystore ->
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("NOLOCKQS_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("NOLOCKQS_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("NOLOCKQS_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // Without a release key, sign with the debug key so the APK stays installable.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

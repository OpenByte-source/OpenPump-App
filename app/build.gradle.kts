// The Android shell: screens, the foreground run service, Bluetooth, notifications,
// widget, file providers. Everything that can be plain Java lives in :core instead.
plugins {
    alias(libs.plugins.android.application)
}

// Monotonic build number: 1000 plus the commit count, so "which build is on this phone?"
// always has an answer. The 1000 keeps the public history (which starts again at one
// commit) above every test build made before it. Falls back to 1 outside a git checkout.
val buildNumber: Int = try {
    1000 + providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
} catch (e: Exception) {
    1
}

// Release signing comes from the environment and nowhere else. CI sets these four from
// the protected `release` environment's secrets (see docs/release.md). On a machine
// without all four, the release build stays unsigned exactly as before, so a local
// `assembleRelease` still works. No key, path or password is ever written in this file.
val releaseSigningVars = listOf(
    "OPENPUMP_KEYSTORE_FILE",
    "OPENPUMP_KEYSTORE_PASSWORD",
    "OPENPUMP_KEY_ALIAS",
    "OPENPUMP_KEY_PASSWORD",
)
val releaseSigning: Map<String, String>? = releaseSigningVars
    .associateWith { providers.environmentVariable(it).orNull.orEmpty() }
    .takeIf { env -> env.values.all { it.isNotBlank() } }

android {
    namespace = "org.openpump"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.openpump"
        minSdk = 24
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.10.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // BuildConfig.DEBUG is how main code knows whether the diagnostic console is in this
    // build. The console (MainActivity) lives in src/debug, so a release APK has neither the
    // class nor its manifest entry; Settings draws its door only when DEBUG is true.
    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("OPENPUMP_KEYSTORE_FILE"))
                storePassword = releaseSigning.getValue("OPENPUMP_KEYSTORE_PASSWORD")
                keyAlias = releaseSigning.getValue("OPENPUMP_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("OPENPUMP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        // Starts as a report; findings get fixed and this flips to true.
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core"))
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Release signing — loaded from keystore.properties (gitignored). Missing
// file means the release build falls back to unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.smithswz.tsphone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.smithswz.tsphone"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        } else {
            logger.warn("keystore.properties missing — release build will be unsigned")
        }
    }

    buildTypes {
        debug {
            // Debug and release share the applicationId so `adb shell run-as` and
            // force-stop commands work identically during development.
        }
        release {
            // Minify off for the first release: JNA/ts3j rely on reflection
            // and R8 rules would need auditing before enabling.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // False positive: MainActivity extends ComponentActivity and launches
        // fine, but lint's class-hierarchy check fails to resolve it.
        disable += "Instantiatable"
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // TeamSpeak 3 client protocol — vendored jar (JitPack SNAPSHOT resolution
    // proved unstable); its transitive deps are stable Maven Central artifacts.
    implementation(files("libs/ts3j.jar"))
    implementation("org.bouncycastle:bcprov-jdk15on:1.67")
    implementation("commons-lang:commons-lang:2.6")
    implementation("dnsjava:dnsjava:2.1.8")
    implementation("org.ini4j:ini4j:0.5.1")

    // JNA runtime for the app's own libopus binding (ticket 05).
    // 5.17+ publishes an AAR with the Android libjnidispatch natives.
    implementation("net.java.dev.jna:jna:5.19.1")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

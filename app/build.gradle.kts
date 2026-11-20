import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.carry"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "app.carry"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = libs.versions.appVersion.get()
    }

    signingConfigs {
        create("sideload") {
            // The same public key on every build, so an update can replace the installed app.
            storeFile = file("carry-sideload.jks")
            storePassword = "carry-sideload"
            keyAlias = "carry"
            keyPassword = "carry-sideload"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

base {
    archivesName.set("carry")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.work.runtime)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.datastore)
    implementation(libs.okhttp)
    implementation(libs.smbj)
    implementation(libs.bcprov)
    implementation(libs.slf4j.nop)
    coreLibraryDesugaring(libs.desugar)
    debugImplementation(libs.compose.ui.tooling)
}

tasks.register<Copy>("exportApk") {
    group = "distribution"
    description = "Copy the sideload APK to dist/carry.apk"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release"))
    include("*.apk")
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "carry.apk" }
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

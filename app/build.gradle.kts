import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.halum"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.halum"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 32-bit is a hard requirement (user device armeabi-v7a).
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("boolean", "HALUM_CAPTURE", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("boolean", "HALUM_CAPTURE", "false")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Models are memory-mapped from APK assets; deflating a 12 MB model is
    // exactly the kind of transient allocation that OOMs a 192 MB heap.
    androidResources {
        noCompress += listOf("tflite", "onnx", "bin")
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
            )
        }
        jniLibs {
            // TFLite and OpenCV both ship >= 4 ABIs; keep only ours.
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("halum.fixtures", rootProject.file("fixtures").absolutePath)
            }
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
        disable += setOf("GradleDependency", "NewerVersionAvailable")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.tflite)
    implementation(libs.tflite.support)
    implementation(libs.opencv)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

/**
 * Records the sha256 + sizes of whatever export put into assets/models so the
 * integration test and ModelStore agree on a version without a manifest file
 * being hand-edited.
 */
val modelsManifest by tasks.registering {
    val assetsDir = layout.projectDirectory.dir("src/main/assets/models")
    val manifestFile = layout.buildDirectory.file("generated/models/MODELS.sha256")
    inputs.dir(assetsDir).optional()
    outputs.file(manifestFile)
    doLast {
        val out = manifestFile.get().asFile
        out.parentFile.mkdirs()
        val files = assetsDir.asFile.listFiles()?.filter { it.isFile && it.length() > 0 } ?: emptyList()
        out.writeText(
            files.sortedBy { it.name }.joinToString("\n") { f ->
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(f.readBytes()).joinToString("") { "%02x".format(it) }
                "${digest}  ${f.length()}  models/${f.name}"
            } + "\n",
        )
    }
}

tasks.named("preBuild") { dependsOn(modelsManifest) }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.forge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.forge.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        viewBinding = true
        dataBinding = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    configurations.all {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }

    // Include javax stubs (needed at runtime on Android where java.compiler module doesn't exist)
    sourceSets {
        getByName("main") {
            java.srcDir("src/main/stubs")
        }
    }
}

// Allow javax stubs to coexist with the JDK's java.compiler module at compile time
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf(
        "--patch-module", "java.compiler=${project.file("src/main/stubs").absolutePath}"
    ))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Core AndroidX
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.annotation:annotation:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.5")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // RecyclerView + CardView
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // UI Utilities
    implementation("com.airbnb.android:lottie:6.6.2")
    implementation("com.facebook.shimmer:shimmer:0.5.0")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Markdown rendering
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:syntax-highlight:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")

    // File operations
    implementation("commons-io:commons-io:2.13.0")

    // Zip handling
    implementation("net.lingala.zip4j:zip4j:2.11.5")

    // ECJ (Eclipse Java Compiler) — bundled for on-device Java compilation
    // Using 3.12.3: oldest version on Maven Central, supports Java 8, no javax.lang.model dependency
    // (Newer ECJ versions reference javax.lang.model.SourceVersion which doesn't exist on Android)
    implementation("org.eclipse.jdt:ecj:3.12.3")

    // R8/D8 — for on-device .class → .dex conversion (replaces system dx which doesn't exist on stock Android)
    implementation("com.android.tools:r8:8.3.37")

    // BouncyCastle — for proper PKCS#7 APK v1 signing (raw signature bytes are rejected by modern Android)
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // javax stubs — must be `implementation` (not compileOnly) so they're packaged into the APK
    // These classes exist in the JDK on the Mac but NOT on Android's runtime
    implementation(project(":stubs-library"))


    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

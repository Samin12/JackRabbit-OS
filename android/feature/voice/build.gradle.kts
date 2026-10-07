plugins { id("com.android.library") }

android {
    namespace = "com.resonolabs.feature.voice"
    compileSdk = 36

    defaultConfig { minSdk = 31 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:input"))
    implementation(project(":core:power"))
    implementation(project(":runtime-host"))
    implementation(project(":feature:genui"))
    implementation("io.github.webrtc-sdk:android:144.7559.09")
    testImplementation("junit:junit:4.13.2")
    // android.jar only ships org.json stubs; JVM tests need the reference implementation.
    testImplementation("org.json:json:20231013")
}

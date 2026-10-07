plugins { id("com.android.library") }

android {
    namespace = "com.resonolabs.feature.compose"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// ComposeSheet: type or dictate into any text field. Dictation streams the microphone to the
// runtime's speech-to-text call over its own WebRTC peer (same SDK as :feature:voice, no
// dependency on it), so text entry never needs the voice session.
dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:input"))
    implementation(project(":runtime-host"))
    implementation("io.github.webrtc-sdk:android:144.7559.09")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub on the JVM; event-routing tests parse real payloads.
    testImplementation("org.json:json:20231013")
}

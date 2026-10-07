plugins { id("com.android.library") }

android {
    namespace = "com.resonolabs.feature.genui"
    compileSdk = 36

    defaultConfig { minSdk = 31 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Model, parser, store and controller are pure Java so they run on the JVM.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:input"))
    implementation(project(":runtime-host"))
    testImplementation("junit:junit:4.13.2")
    // android.jar only ships org.json stubs; the JVM tests need the real implementation.
    testImplementation("org.json:json:20160810")
}

plugins { id("com.android.library") }
android {
    namespace = "com.resonolabs.feature.t3"
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
    implementation(project(":runtime-host"))
    implementation(project(":feature:compose"))
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub on the JVM; tests parse real payloads with the reference jar.
    testImplementation("org.json:json:20231013")
}

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "org.scrctl.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.scrctl.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets["main"].java.srcDir(rootProject.file("xdb/android/src/main/java"))
    buildTypes { getByName("release") { proguardFiles(rootProject.file("xdb/android/consumer-rules.pro")) } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("native-libs"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("native-assets"))
    packaging { jniLibs.useLegacyPackaging = false }
}
val buildNative by tasks.registering(Exec::class) {
    workingDir(rootDir)
    commandLine("python3", "tools/build_native.py")
}
tasks.named("preBuild").configure { dependsOn(buildNative) }
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

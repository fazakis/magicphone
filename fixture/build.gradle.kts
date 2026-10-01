plugins { id("com.android.application") }
android {
    namespace = "dev.magicphone.fixture"
    compileSdk = 36
    defaultConfig { applicationId = "dev.magicphone.fixture"; minSdk = 30; targetSdk = 36; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
}
dependencyLocking { lockAllConfigurations() }

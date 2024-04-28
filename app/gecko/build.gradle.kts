plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phlox.tvwebbrowser.webengine.gecko"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":app:common"))

    implementation("androidx.appcompat:appcompat:1.6.1")

    //val geckoViewChannel = "beta"
    //val geckoViewVersion = "112.0.20230330182947"
    //implementation("org.mozilla.geckoview:geckoview-$geckoViewChannel:$geckoViewVersion")
    val geckoViewVersion = "121.0.20240108143603"
    implementation("org.mozilla.geckoview:geckoview:$geckoViewVersion")

    testImplementation("junit:junit:4.13.2")
}
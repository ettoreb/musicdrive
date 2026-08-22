import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ettore.musicdrive"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ettore.musicdrive"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val props = Properties()
        val propertiesFile = rootProject.file("local.properties")
        if (propertiesFile.exists()) {
            propertiesFile.inputStream().use { props.load(it) }
        }
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${props.getProperty("GOOGLE_WEB_CLIENT_ID") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.ui.tooling)
    // new from claude
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")
    implementation("com.google.android.gms:play-services-auth:21.6.0")

    implementation("com.google.api-client:google-api-client-android:2.7.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20240914-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.database)
    implementation(libs.androidx.media3.common)
}
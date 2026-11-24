plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

// JitPack builds with GROUP=com.github.gatehillsreg and VERSION=<tag>
group = System.getenv("GROUP") ?: "ae.livechat"
version = "1.0.0"

android {
    namespace = "ae.livechat.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$version\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    testImplementation(libs.json)   // the real org.json: android.jar has only stubs on the JVM
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "livechat-android-sdk"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("livechat.ae Android SDK")
                description.set("The livechat.ae chat for Android apps")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}

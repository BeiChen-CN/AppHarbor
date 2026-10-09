plugins {
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(ProjectConfig.JVM_VERSION)

    android {
        compileSdk { version = release(ProjectConfig.Android.COMPILE_SDK) }
        minSdk = ProjectConfig.Android.MIN_SDK
        namespace = "${ProjectConfig.PACKAGE_NAME}.data"
    }

    jvm("desktop")

    sourceSets {
        val jvmMain = create("jvmMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.jbcrypt)
            }
        }
        commonMain.dependencies {
            implementation(projects.domain)
            implementation(libs.koin.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
        }
        androidMain {
            dependsOn(jvmMain)
            dependencies {
                implementation(libs.androidx.datastore.preferences)
                implementation(libs.hiddenapibypass)
                implementation(libs.rikka.shizuku.api)
                implementation(libs.rikka.shizuku.provider)
                implementation(libs.ktor.client.okhttp)
            }
        }
        named("desktopMain") {
            dependsOn(jvmMain)
        }
        named("desktopTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

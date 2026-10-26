plugins {
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

val generatedSrcDir = layout.buildDirectory.dir("generated/projectConfig")

kotlin {
    jvmToolchain(ProjectConfig.JVM_VERSION)

    android {
        androidResources.enable = true
        compileSdk {
            version = release(ProjectConfig.Android.COMPILE_SDK) {
                minorApiLevel = ProjectConfig.Android.COMPILE_SDK_MINOR
            }
        }
        minSdk = ProjectConfig.Android.MIN_SDK
        namespace = "${ProjectConfig.PACKAGE_NAME}.app.shared"
    }

    jvm("desktop")

    sourceSets {
        val desktopMain = getByName("desktopMain")
        val commonMain = getByName("commonMain") {
            kotlin.srcDir(generatedSrcDir.map { it.dir("kotlin") })
        }
        commonMain.dependencies {
            implementation(projects.domain)
            api(libs.miuix.ui)
            implementation(libs.miuix.blur)
            implementation(libs.miuix.icons)
            implementation(libs.miuix.preference)
            implementation(libs.miuix.nav)
            implementation(libs.miuix.squircle)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime)
            implementation(libs.androidx.navigationevent)
            implementation(libs.jetbrains.components.resources)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.compose.media.player)
            implementation(libs.ktor.client.core)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity)
            implementation(libs.androidx.core.ktx)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
        }

        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "${ProjectConfig.PACKAGE_NAME}.resources"
}

composeCompiler {
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("app/shared/compose_compiler_config.conf")
    )
}

val generateVersionInfo = tasks.register<GenerateVersionInfoTask>("generateVersionInfo") {
    description = "generateVersionInfo"
    versionName.set(ProjectConfig.VERSION_NAME)
    versionCode.set(resolveVersionCode())
    appName.set(ProjectConfig.APP_NAME)
    packageName.set(ProjectConfig.PACKAGE_NAME)
    outputFile.set(generatedSrcDir.map { it.file("kotlin/misc/VersionInfo.kt") })
}

tasks.named("generateComposeResClass").configure {
    dependsOn(generateVersionInfo)
}

@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.impl.VariantOutputImpl
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.app.shared)
    implementation(projects.domain)
    implementation(projects.data)
    implementation(libs.koin.core)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.ktor.client.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.coil.singleton)
    implementation(libs.hiddenapibypass)
    implementation(libs.rikka.shizuku.provider)
    implementation(libs.rikka.shizuku.api)
    implementation(libs.focus.api)
}

android {
    namespace = ProjectConfig.PACKAGE_NAME
    compileSdk {
        version = release(ProjectConfig.Android.COMPILE_SDK) {
            minorApiLevel = ProjectConfig.Android.COMPILE_SDK_MINOR
        }
    }

    defaultConfig {
        applicationId = ProjectConfig.PACKAGE_NAME
        minSdk = ProjectConfig.Android.MIN_SDK
        targetSdk = ProjectConfig.Android.TARGET_SDK
        versionCode = resolveVersionCode()
        versionName = ProjectConfig.VERSION_NAME

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    val properties = Properties()
    runCatching { project.rootProject.file("local.properties").reader(Charsets.UTF_8).use(properties::load) }

    // 凭据优先读环境变量（CI 与本地开发都适用），local.properties 仅作本地兜底，
    // 避免明文密码成为唯一来源。
    fun credential(key: String): String? =
        System.getenv(key)?.takeIf { it.isNotBlank() } ?: properties.getProperty(key)

    val keystorePath = credential("KEYSTORE_PATH")
    val keystorePwd = credential("KEYSTORE_PASS")
    val alias = credential("KEY_ALIAS")
    val pwd = credential("KEY_PASSWORD")
    if (keystorePath != null) {
        signingConfigs {
            register("github") {
                storeFile = file(keystorePath)
                storePassword = keystorePwd
                keyAlias = alias
                keyPassword = pwd
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    } else {
        signingConfigs {
            register("release") {
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules-android.pro")
            signingConfig = signingConfigs.getByName(if (keystorePath != null) "github" else "debug")
        }
        debug {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("github")
        }
    }

    androidResources {
        localeFilters.addAll(listOf("en", "zh"))
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        jniLibs {
            excludes += "lib/*/libandroidx.graphics.path.so"
        }
        resources {
            excludes += arrayOf(
                "/META-INF/*",
                "/META-INF/androidx/**",
                "/META-INF/versions/**",
                "/org/bouncycastle/**",
                "/org/apache/commons/**",
                "/kotlin/**",
                "/kotlinx/**",
                "/okhttp3/**",
                "/*.txt",
                "/*.bin",
                "/*.json",
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? VariantOutputImpl)?.outputFileName?.set(
                output.versionName.zip(output.versionCode) { versionName, versionCode ->
                    // 用 `-<code>` 而不是 `(<code>)`：GitHub Release 会把括号改写掉，
                    // 应用内更新检查靠文件名里的 versionCode 免去下载 APK 才能判断版本。
                    val suffix = if (variant.buildType == "debug") "_debug" else ""
                    "${ProjectConfig.APP_NAME}-v${versionName}-${versionCode}${suffix}.apk"
                }
            )
        }
    }
}

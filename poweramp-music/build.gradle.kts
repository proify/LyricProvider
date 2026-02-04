/*
 * PowerAmp Provider Build Script
 */

import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties
import java.io.FileInputStream

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

// 加载签名配置文件的辅助函数
fun getSigningProperties(): Properties {
    val properties = Properties()
    val propertiesFile = file("signing.properties")
    if (propertiesFile.exists()) {
        properties.load(FileInputStream(propertiesFile))
    }
    return properties
}

val signingProps = getSigningProperties()

configure<ApplicationExtension> {
    namespace = "io.github.proify.lyricon.paprovider"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.proify.lyricon.paprovider"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        resources {
            excludes += "META-INF/**"
        }
    }

    signingConfigs {
        create("release") {
            // 只有当配置文件存在且包含相关key时才配置签名
            if (signingProps.isNotEmpty() && signingProps.containsKey("store_file")) {
                storeFile = file(signingProps.getProperty("store_file"))
                storePassword = signingProps.getProperty("store_password")
                keyAlias = signingProps.getProperty("key_alias")
                keyPassword = signingProps.getProperty("key_password")
            } else {
                println("⚠️ Warning: signing.properties not found. Release build will not be signed.")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 只有在配置了签名信息时才应用
            if (signingProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        // 开启 ViewBinding，用于 SettingsActivity
        viewBinding = true
    }
}

dependencies {
    implementation(project(":share:common"))
    implementation(project(":share:lrckit"))
    implementation(project(":share:cloudlyric"))
    // YukiHookAPI
    implementation(libs.yukihookapi.api)
    ksp(libs.yukihookapi.ksp.xposed)

    compileOnly(libs.xposed.api)
    implementation(libs.androidx.core.ktx)

    // === 使用具体版本号替代未定义的 libs 引用 ===
    // Material Design 组件 (用于 Switch, CardView 等)
    implementation("com.google.android.material:material:1.12.0")
    // ConstraintLayout (SettingsActivity 布局可能需要)
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    // AppCompat
    implementation("androidx.appcompat:appcompat:1.7.0")
    // ====================================================

    // 音频标签解析库
    implementation("net.jthink:jaudiotagger:3.0.1")

    // 1. 模型定义
    implementation("io.github.proify.lyricon.lyric:model:0.1.66")
    
    // 2. Provider
    implementation("io.github.proify.lyricon:provider:0.1.66")
}
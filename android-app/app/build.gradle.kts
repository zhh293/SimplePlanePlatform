import org.gradle.api.tasks.Exec
import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Release 签名材料解析（按优先级，缺失时优雅降级，不影响 debug 构建）：
//   1. 环境变量（CI 用：ANDROID_KEYSTORE_PATH/PASSWORD、ANDROID_KEY_ALIAS/PASSWORD）
//   2. 项目根的 keystore.properties（本地用，已在 .gitignore 中排除，不入库）
// 三者都缺失时 releaseSigning 为 null，assembleRelease 退回 unsigned 产物。
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

fun signingValue(envKey: String, propKey: String): String? =
    (System.getenv(envKey)?.takeIf { it.isNotBlank() })
        ?: (keystoreProps.getProperty(propKey)?.takeIf { it.isNotBlank() })

val ksPath = signingValue("ANDROID_KEYSTORE_PATH", "storeFile")
val ksPassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val ksAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
val ksKeyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = ksPath != null && ksPassword != null && ksAlias != null && ksKeyPassword != null

android {
    namespace = "com.proxy.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.proxy.android"
        minSdk = 24            // Android 7.0（任务文档 0.2）
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 只打包当前关注的 ABI；与 scripts/build-rust.sh 产出的 jniLibs 子目录对齐。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // 仅当签名材料齐备时才注册 release 签名配置；否则不注册，release 退回 unsigned。
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // ksPath 支持绝对路径或相对仓库根目录的路径。
                storeFile = rootProject.file(ksPath!!)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 有签名材料则签名，输出可直接安装的 app-release.apk；
            // 无材料时保持 unsigned（app-release-unsigned.apk），不阻断构建。
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("[signing] 未找到 release 签名材料，将产出 unsigned APK。")
                null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // jniLibs 由 cargo-ndk（scripts/build-rust.sh）写入 src/main/jniLibs/<abi>/libplane_core.so，
    // Gradle 默认会从 src/main/jniLibs 打包，这里显式声明以便阅读。
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

// 把 Rust .so 的构建挂到 preBuild 之前，保证 assemble 时 jniLibs 已就绪。
// MVP 阶段：若本机缺 cargo-ndk，任务会失败提示——CI 中由专门步骤保证（见 Task Q3）。
// 通过 -PskipRustBuild=true 可跳过（例如已手动跑过 build-rust.sh）。
val buildRust by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compile plane-core (Rust) into jniLibs via cargo-ndk"
    workingDir = rootProject.projectDir.parentFile // 仓库根目录（android-app 的上一级）
    val profile = if (project.hasProperty("rustRelease")) "release" else "debug"
    val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val bashExecutable = if (isWindows) {
        // Windows' PATH may resolve `bash` to WSL, which cannot reliably consume
        // this checkout's paths. Prefer an explicit Git Bash installation.
        val configured = providers.gradleProperty("gitBash").orNull
            ?: System.getenv("GIT_BASH")
        val candidates = listOfNotNull(
            configured?.let(::File),
            System.getenv("ProgramFiles")?.let { File(it, "Git/bin/bash.exe") },
            System.getenv("ProgramFiles(x86)")?.let { File(it, "Git/bin/bash.exe") },
            System.getenv("LOCALAPPDATA")?.let { File(it, "Programs/Git/bin/bash.exe") },
        )
        candidates.firstOrNull(File::isFile)?.absolutePath
            ?: throw GradleException(
                "Git Bash was not found. Install Git for Windows or set " +
                    "GIT_BASH / -PgitBash=C:\\path\\to\\bash.exe",
            )
    } else {
        "bash"
    }
    commandLine(bashExecutable, "scripts/build-rust.sh", profile)
}

tasks.named("preBuild") {
    if (!project.hasProperty("skipRustBuild")) {
        dependsOn(buildRust)
    }
}

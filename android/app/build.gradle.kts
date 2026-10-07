plugins {
    id("com.android.application")
}

// Per-title identity, handed over by the workflow (or a local build):
//   -PrexName=scottpilgrim -PrexTitle="Scott Pilgrim vs. The World" -PrexTitleId=58410A2C
//   -PrexPortDir=/abs/path/autoports/<name>/port  -PrexSdkDir=/abs/path/rexglue-sdk
val rexName: String = (project.findProperty("rexName") as String?) ?: "game"
val rexTitle: String = (project.findProperty("rexTitle") as String?) ?: rexName
val rexTitleId: String = (project.findProperty("rexTitleId") as String?) ?: "00000000"
val rexPortDir: String = (project.findProperty("rexPortDir") as String?)
    ?: "${rootProject.projectDir.parentFile}/autoports/$rexName/port"
val rexSdkDir: String = (project.findProperty("rexSdkDir") as String?)
    ?: "${rootProject.projectDir}/sdk/rexglue-sdk"
val rexVersionCode: Int = ((project.findProperty("rexVersionCode") as String?) ?: "1").toInt()
val rexVersionName: String = (project.findProperty("rexVersionName") as String?) ?: "1.0"

android {
    namespace = "com.rexauto.port"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.rexauto.port.$rexName"
        minSdk = 28
        targetSdk = 35
        versionCode = rexVersionCode
        versionName = rexVersionName

        resValue("string", "app_name", rexTitle)
        buildConfigField("String", "GAME_TITLE", "\"${rexTitle.replace("\"", "\\\"")}\"")
        buildConfigField("String", "TITLE_ID", "\"$rexTitleId\"")
        buildConfigField("String", "PROJECT", "\"$rexName\"")

        // Android ARM64 only.
        // NEON/SIMD is part of the ARMv8-A baseline used by arm64-v8a.
        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DREX_PROJECT_ROOT=${rootProject.projectDir.parentFile.absolutePath}",
                    "-DREXSDK_DIR=$rexSdkDir",
                    "-DREX_PORT_DIR=$rexPortDir",
                    "-DREX_APP_NAME=$rexName",
                )

                cppFlags += listOf(
                    "-std=c++23"
                )
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.1"
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }

    signingConfigs {
        create("ci") {
            val ks = file("${rootProject.projectDir}/ci-release.jks")

            if (ks.exists()) {
                storeFile = ks
                storePassword = "rexauto"
                keyAlias = "rexauto"
                keyPassword = "rexauto"
            }
        }
    }

    buildTypes {
        release {
            // R8 optimizes Java/Kotlin bytecode and removes unused code.
            isMinifyEnabled = true

            // Removes Android resources that are not referenced.
            isShrinkResources = true

            // Keep R8's optimized Android configuration.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Native C++ optimization.
            externalNativeBuild {
                cmake {
                    cppFlags += listOf(
                        "-O3",
                        "-DNDEBUG"
                    )
                }
            }

            if (file("${rootProject.projectDir}/ci-release.jks").exists()) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }

        debug {
            // Keep debugging/profiling usable.
            isMinifyEnabled = false
            isShrinkResources = false
            isJniDebuggable = true

            externalNativeBuild {
                cmake {
                    cppFlags += listOf(
                        "-O0"
                    )
                }
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
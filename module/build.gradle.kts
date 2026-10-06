// FlutterInspect native Zygisk module.
// Forked from FlutterTap by Eduardo Lopes. FlutterInspect by Akshay Khilari.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.payatu.flutterlens.module"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        // Android 10 (API 29) and up. Resolution logic only covers arm64/x86_64
        // (same scope as the original FlutterTap it was forked from).
        minSdk = 29

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        prefab = true
    }
}

dependencies {
    implementation("io.github.vvb2060.ndk:dobby:1.2")
}

tasks.register<Zip>("packageMagiskZip") {
    dependsOn("stripReleaseDebugSymbols")
    archiveBaseName.set("FlutterInspect")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("dist"))
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    from("template")

    listOf("arm64-v8a", "x86_64").forEach { abi ->
        from(layout.buildDirectory.dir("intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/$abi")) {
            include("libflutterlens.so")
            rename { "$abi.so" }
            into("zygisk")
        }
    }
}

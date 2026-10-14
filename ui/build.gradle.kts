plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.1"
}

kotlin {
    js {
        outputModuleName.set("mihomo-ui")
        useCommonJs()
        browser {
            commonWebpackConfig {
                outputFileName = "mihomo-ui.js"
                devtool = "source-map"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.12.1")
                implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
                implementation("org.jetbrains.compose.animation:animation:1.12.1")
                implementation("org.jetbrains.compose.ui:ui:1.12.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
                implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
                implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
                implementation("top.yukonga.miuix.kmp:miuix-nav:0.9.4")
            }
        }
    }
}

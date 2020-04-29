import java.net.URL

plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

repositories {
    google()
}

description = "WebSocket client API used by the Krossbow STOMP client, with default JS and JVM implementations."

android {
    compileSdkVersion = "28"
}

kotlin {
    jvm()
    js {
        useCommonJs()
        nodejs()
        browser()
    }
    android()
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core-common:${Versions.coroutines}")
                implementation("org.jetbrains.kotlinx:kotlinx-io:${Versions.kotlinxIO}")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${Versions.coroutines}")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(kotlin("stdlib-jdk8"))
                api("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${Versions.coroutines}")
                implementation("org.jetbrains.kotlinx:kotlinx-io-jvm:${Versions.kotlinxIO}")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
                implementation("com.pusher:java-websocket:1.4.1")
            }
        }
        val androidMain by getting {
            dependsOn(jvmMain)
        }
        val jsMain by getting {
            dependencies {
                implementation(kotlin("stdlib-js"))
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core-js:${Versions.coroutines}")
                implementation("org.jetbrains.kotlinx:kotlinx-io-js:${Versions.kotlinxIO}")
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
            }
        }
    }
}

tasks.dokka {
    multiplatform {
        val global by creating {}
        val jvm by creating {
            externalDocumentationLink {
                url = URL("https://docs.oracle.com/en/java/javase/11/docs/api/")
                packageListUrl = URL(url, "element-list")
            }
        }
        val js by creating {}
    }
}

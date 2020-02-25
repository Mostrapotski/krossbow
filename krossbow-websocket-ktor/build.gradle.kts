plugins {
    kotlin("multiplatform")
}

description = "Krossbow adapter for Ktor WebSocket client"

val coroutinesVersion = "1.3.3"
val ktorVersion = "1.3.0"

kotlin {
    jvm()
    js {
        nodejs()
        browser()
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
                api(project(":krossbow-websocket-api"))
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core-common:$coroutinesVersion")
                implementation("io.ktor:ktor-client-websockets:$ktorVersion")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(kotlin("stdlib-jdk8"))
                api("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:$coroutinesVersion")
                implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
            }
        }
        val jsMain by getting {
            dependencies {
                implementation(kotlin("stdlib-js"))
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core-js:$coroutinesVersion")
                implementation("io.ktor:ktor-client-js:$ktorVersion")
                implementation(npm("text-encoding")) // seems required by ktor (nodeJS)
                implementation(npm("bufferutil")) // seems required by ktor (browser)
                implementation(npm("fs")) // seems required by ktor (browser)
                implementation(npm("utf-8-validate")) // seems required by ktor (browser)
                implementation(npm("abort-controller")) // seems required by ktor (browser)
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
            }
        }
    }
}

import java.net.URL

plugins {
    kotlin("jvm")
}

description = "A Krossbow adapter for Spring's default WebSocket client and SockJS client"

dependencies {
    api(project(":krossbow-websocket-core"))

    api("org.slf4j:slf4j-api:1.7.26")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${Versions.coroutines}")

    // For Spring's WebSocket clients
    api("org.springframework:spring-websocket:5.2.3.RELEASE")

    // JSR 356 - Java API for WebSocket (reference implementation)
    // Low-level implementation required by Spring's client
    implementation("org.glassfish.tyrus.bundles:tyrus-standalone-client-jdk:1.15")
}

tasks.dokka {
    dependsOn(":krossbow-websocket-core:dokka")
    configuration {
        externalDocumentationLink {
            url = URL("https://docs.spring.io/spring/docs/current/javadoc-api/")
            packageListUrl = URL(url, "package-list")
        }
        externalDocumentationLink {
            url = relativeDokkaUrl("krossbow-websocket-core")
            packageListUrl = relativeDokkaPackageListUrl("krossbow-websocket-core")
        }
    }
}

val dokkaJar by tasks.creating(Jar::class) {
    archiveClassifier.set("javadoc")
    from(tasks.dokka)
}

val sourcesJar by tasks.creating(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            from(components["kotlin"])
            artifact(dokkaJar)
            artifact(sourcesJar)
        }
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.10"
    id("com.github.ben-manes.versions") version "0.47.0"
    id("org.gradle.maven-publish")
}

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}

group = "ru.krikun.kotlin"
version = "0.0.4"

kotlin {
    jvmToolchain(17)
}

val sourcesJar = task<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets["main"].allSource)
}

configure<PublishingExtension> {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
        artifact(sourcesJar) { classifier = "sources" }
    }
}

tasks.wrapper { distributionType = Wrapper.DistributionType.ALL }

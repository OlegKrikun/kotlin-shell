plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("com.github.ben-manes.versions") version "0.53.0"
    id("dev.detekt") version "2.0.0-alpha.2"
    id("org.gradle.maven-publish")
}

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")

    detektPlugins("dev.detekt:detekt-rules-ktlint-wrapper:2.0.0-alpha.2")
}

group = "ru.krikun.kotlin"
version = "0.0.4"

kotlin {
    jvmToolchain(21)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$projectDir/detekt.yml")
}

val sourcesJar = tasks.register<Jar>("sourcesJar") {
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

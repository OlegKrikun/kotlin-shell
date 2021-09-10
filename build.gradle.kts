import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.5.30"
    id("com.github.ben-manes.versions") version "0.39.0"
    id("org.gradle.maven-publish")
}

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}

group = "ru.krikun.kotlin"
version = "0.0.4"

tasks.withType(KotlinCompile::class) {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xopt-in=kotlin.RequiresOptIn")
    }
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

import com.jfrog.bintray.gradle.BintrayExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.3.50"
    id("com.jfrog.bintray") version "1.8.4"
    id("org.gradle.maven-publish")
}

repositories { jcenter() }

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.3.50")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.2")

    testImplementation("junit:junit:4.12")
}

group = "ru.krikun.kotlin"
version = "0.0.0"

tasks.getByName<KotlinCompile>("compileKotlin") {
    kotlinOptions.freeCompilerArgs = listOf(
        "-XXLanguage:+NewInference",
        "-Xuse-experimental=kotlinx.coroutines.ExperimentalCoroutinesApi",
        "-Xuse-experimental=kotlinx.coroutines.FlowPreview"
    )
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

configure<BintrayExtension> {
    val properties = properties("bintray.properties")
    val bintrayUser: String = properties.getProperty("user")
    val bintrayKey: String = properties.getProperty("key")

    user = bintrayUser
    key = bintrayKey

    pkg.apply {
        repo = "maven"
        name = project.name
        setLicenses("Apache-2.0")
        websiteUrl = "https://github.com/OlegKrikun/kotlin-shell"
        issueTrackerUrl = "https://github.com/OlegKrikun/kotlin-shell/issues"
        vcsUrl = "https://github.com/OlegKrikun/kotlin-shell.git"

        setPublications("maven")
    }
}

fun properties(path: String) = Properties().apply { file(path).inputStream().use { load(it) } }

tasks.wrapper { distributionType = Wrapper.DistributionType.ALL }

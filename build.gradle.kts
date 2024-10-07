import dev.extframework.gradle.common.*
import dev.extframework.gradle.common.dm.artifactResolver
import dev.extframework.gradle.common.dm.jobs

plugins {
    kotlin("jvm") version "2.0.0"
    id("maven-publish")
    id("org.jetbrains.dokka") version "1.9.10"
    java
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("dev.extframework.common") version "1.0.24"
}

group = "net.yakclient"
version = "1.0.1-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    extFramework()
}

application {
    mainClass = "net.yakclient.client.MainKt"
}

kotlin {
    explicitApi()
}

tasks.wrapper {
    gradleVersion = "8.5"
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.7.22")

    toolingApi()
    jobs()
    archives()
    boot()
    objectContainer()
    artifactResolver(maven = true)
    commonUtil()
    extLoader(version = "2.1.5-SNAPSHOT")
    minecraftBootstrapper()

    testImplementation(kotlin("test"))
}


abstract class ListAllDependencies : DefaultTask() {
    init {
        // Define the output file within the build directory
        val outputFile = project.buildDir.resolve("resources/main/dependencies.txt")
        outputs.file(outputFile)
    }

    @TaskAction
    fun listDependencies() {
        val outputFile = project.buildDir.resolve("resources/main/dependencies.txt")
        // Ensure the directory for the output file exists
        outputFile.parentFile.mkdirs()
        // Clear or create the output file
        outputFile.writeText("")

        val set = HashSet<String>()

        // Process each configuration that can be resolved
        project.configurations.filter { it.isCanBeResolved }.forEach { configuration ->
            println("Processing configuration: ${configuration.name}")
            try {
                configuration.resolvedConfiguration.firstLevelModuleDependencies.forEach { dependency ->
                    collectDependencies(dependency, set)
                }
            } catch (e: Exception) {
                println("Skipping configuration '${configuration.name}' due to resolution errors.")
            }
        }

        set.add("${this.project.group}:minecraft-bootstrapper:${this.project.version}\n")

        set.forEach {
            outputFile.appendText(it)
        }
    }

    private fun collectDependencies(dependency: ResolvedDependency, set: MutableSet<String>) {
        set.add("${dependency.moduleGroup}:${dependency.moduleName}:${dependency.moduleVersion}\n")
        dependency.children.forEach { childDependency ->
            collectDependencies(childDependency, set)
        }
    }
}

// Register the custom task in the project
val listAllDependencies by tasks.registering(ListAllDependencies::class)

tasks.compileKotlin {
    dependsOn(listAllDependencies)
}

tasks.jar {
    from(tasks.named("listAllDependencies"))
}

tasks.shadowJar {
    from(tasks.named("listAllDependencies"))
}

common {
    defaultJavaSettings()

    publishing {
        publication {
            withSources()
            withDokka()
            artifact(tasks.shadowJar)

            artifactId = "client"
        }
        repositories {
            extFramework(credentials = propertyCredentialProvider)
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
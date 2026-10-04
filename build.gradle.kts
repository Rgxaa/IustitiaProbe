plugins {
    id("fabric-loom") version "1.17.11"
    kotlin("jvm") version "2.3.10"
}

version = "0.1.0"
group = "dev.iustitia.probe"

base { archivesName.set("iustitia-probe") }

loom {
    mixin {
        // Same setup as ../Iustitia: Kotlin mixins aren't seen by the legacy javac Mixin AP,
        // so the non-legacy path remaps mixin refs during remapJar instead.
        useLegacyMixinAp = false
    }
}

repositories {
    maven("https://maven.isxander.dev/releases")   // YACL (Iustitia's runtime dependency)
    maven("https://maven.fabricmc.net")
    maven("https://api.modrinth.com/maven") {      // Iustitia
        content { includeGroup("maven.modrinth") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    modImplementation("dev.isxander:yet-another-config-lib:${property("yacl_version")}")

    // Iustitia itself, resolved like any other mod dependency (version in gradle.properties)
    modImplementation("maven.modrinth:iustitia:${property("iustitia_version")}")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

kotlin {
    jvmToolchain(21)
}

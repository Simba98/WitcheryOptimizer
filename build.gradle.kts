
import java.util.jar.Manifest
import java.util.zip.ZipFile
import groovy.json.JsonSlurper

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

version = "0.2.3"

val validateProductionJar by tasks.registering {
    group = "verification"
    notCompatibleWithConfigurationCache("Validates the final archive with ZipFile")
    dependsOn(tasks.named("reobfJar"))
    doLast {
        val jar = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        check(jar.isFile) { "Production jar does not exist: $jar" }
        ZipFile(jar).use { zip ->
            val manifest = Manifest(zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF")))
            val config = manifest.mainAttributes.getValue("MixinConfigs")
            check(config == "mixins.witcheryoptimizer.json") { "Invalid MixinConfigs manifest entry: $config" }
            val entry = zip.getEntry(config) ?: error("Missing $config")
            val json = zip.getInputStream(entry).bufferedReader().readText()
            JsonSlurper().parseText(json)
            val refmap = Regex("\"refmap\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                ?: error("Mixin config has no refmap")
            check(zip.getEntry(refmap) != null) { "Missing referenced refmap $refmap" }
            val packageName = Regex("\"package\"\\s*:\\s*\"([^\"]+)\"").find(json)!!.groupValues[1]
            Regex("\"(?:mixins|server)\"\\s*:\\s*\\[([^]]*)]", RegexOption.DOT_MATCHES_ALL).findAll(json)
                .flatMap { Regex("\"([^\"]+)\"").findAll(it.groupValues[1]).map { match -> match.groupValues[1] } }
                .forEach { check(zip.getEntry(packageName.replace('.', '/') + "/" + it + ".class") != null) { "Missing mixin class $it" } }
            val tileMixin = zip.getInputStream(zip.getEntry(packageName.replace('.', '/') + "/MixinTileEntity.class")).readBytes()
            check(tileMixin.toString(Charsets.ISO_8859_1).contains("onChunkUnload()V")) {
                "Production MixinTileEntity lost the Forge-added, unobfuscated onChunkUnload()V target"
            }
        }
    }
}

tasks.named("check") { dependsOn(validateProductionJar) }
tasks.named<Jar>("sourcesJar") { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }

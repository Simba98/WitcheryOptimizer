
import java.util.jar.Manifest
import java.util.zip.ZipFile
import groovy.json.JsonSlurper

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

version = "0.2.4"

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
            val refmapJson = zip.getInputStream(zip.getEntry(refmap)).bufferedReader().readText()
            check(refmapJson.contains("func_149749_a")) {
                "Refmap lost BlockPoppetShelf.breakBlock mapping"
            }
            check(refmapJson.contains("func_150807_a")) {
                "Refmap lost Chunk.func_150807_a mapping"
            }
            check(refmapJson.contains("func_73239_e")) {
                "Refmap lost ChunkProviderServer.safeLoadChunk mapping"
            }
            check(refmapJson.contains("MixinChunk")) {
                "Refmap lost MixinChunk mappings"
            }
            check(refmapJson.contains("func_70301_a")) {
                "Refmap lost IInventory.getStackInSlot mapping"
            }
            check(refmapJson.contains("func_70304_b")) {
                "Refmap lost IInventory.getStackInSlotOnClosing mapping"
            }
            val packageName = Regex("\"package\"\\s*:\\s*\"([^\"]+)\"").find(json)!!.groupValues[1]
            Regex("\"(?:mixins|server)\"\\s*:\\s*\\[([^]]*)]", RegexOption.DOT_MATCHES_ALL).findAll(json)
                .flatMap { Regex("\"([^\"]+)\"").findAll(it.groupValues[1]).map { match -> match.groupValues[1] } }
                .forEach { check(zip.getEntry(packageName.replace('.', '/') + "/" + it + ".class") != null) { "Missing mixin class $it" } }
            val tileMixin = zip.getInputStream(zip.getEntry(packageName.replace('.', '/') + "/MixinTileEntity.class")).readBytes()
            check(tileMixin.toString(Charsets.ISO_8859_1).contains("onChunkUnload()V")) {
                "Production MixinTileEntity lost the Forge-added, unobfuscated onChunkUnload()V target"
            }
            val providerMixin = zip.getInputStream(zip.getEntry(packageName.replace('.', '/') + "/MixinChunkProviderServer.class")).readBytes()
            check(providerMixin.toString(Charsets.ISO_8859_1).let {
                it.contains("syncChunkLoad") && it.contains("safeLoadChunk")
            }) {
                "Production MixinChunkProviderServer lost a disk-load observation target"
            }
            val shelfMixin = zip.getInputStream(zip.getEntry(packageName.replace('.', '/') + "/MixinPoppetShelf.class")).readBytes()
            check(shelfMixin.toString(Charsets.ISO_8859_1).let {
                it.contains("getStackInSlotOnClosing") && it.contains("markDirty")
            }) {
                "Production MixinPoppetShelf lost the closing-slot markDirty injection selector"
            }
        }
    }
}

tasks.named("check") { dependsOn(validateProductionJar) }
tasks.named<Jar>("sourcesJar") { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }

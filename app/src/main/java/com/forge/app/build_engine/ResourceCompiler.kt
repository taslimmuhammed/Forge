package com.forge.app.build_engine

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Compiles Android resources (strings.xml, colors.xml, layouts, manifest) into
 * binary format without aapt/aapt2.
 *
 * Produces:
 * - resources.arsc (resource table)
 * - Binary XML versions of all XML files
 * - R.java with resource ID constants
 *
 * Limitations:
 * - Does not support 9-patch images, custom styles with parent inheritance chains,
 *   or complex resource qualifiers (only default config)
 */
class ResourceCompiler(private val xmlEncoder: BinaryXmlEncoder = BinaryXmlEncoder()) {

    companion object {
        private const val TAG = "ResourceCompiler"

        // Resource type IDs (matching aapt conventions)
        private const val TYPE_ID_ATTR = 0x01
        private const val TYPE_ID_DRAWABLE = 0x02
        private const val TYPE_ID_MIPMAP = 0x03
        private const val TYPE_ID_LAYOUT = 0x04
        private const val TYPE_ID_ANIM = 0x05
        private const val TYPE_ID_STRING = 0x06
        private const val TYPE_ID_COLOR = 0x07
        private const val TYPE_ID_DIMEN = 0x08
        private const val TYPE_ID_STYLE = 0x09
        private const val TYPE_ID_ID = 0x0A
        private const val TYPE_ID_BOOL = 0x0B
        private const val TYPE_ID_INTEGER = 0x0C
        private const val TYPE_ID_ARRAY = 0x0D
        private const val TYPE_ID_XML = 0x0E
        private const val TYPE_ID_MENU = 0x0F

        // Package ID for app resources
        private const val APP_PACKAGE_ID = 0x7F

        // Chunk types for resources.arsc
        private const val RES_TABLE_TYPE = 0x0002.toShort()
        private const val RES_STRING_POOL_TYPE = 0x0001.toShort()
        private const val RES_TABLE_PACKAGE_TYPE = 0x0200.toShort()
        private const val RES_TABLE_TYPE_SPEC_TYPE = 0x0202.toShort()
        private const val RES_TABLE_TYPE_TYPE = 0x0201.toShort()
    }

    data class ResourceEntry(
        val type: String,      // "string", "color", "layout", etc.
        val name: String,      // resource name
        val value: String?,    // value for value resources, filename for file resources
        val typeId: Int,       // numeric type ID
        val entryId: Int       // entry index within type
    )

    /**
     * Compile all resources and produce a resources.ap_ file.
     *
     * @param resDir The res/ directory of the project
     * @param manifestFile The AndroidManifest.xml file
     * @param outputApk The output resources.ap_ file
     * @param packageName The app's package name
     * @return Map of resource type → name → ID for R.java generation
     */
    fun compile(resDir: File, manifestFile: File, outputApk: File, packageName: String): Map<String, Map<String, Int>> {
        val resources = mutableListOf<ResourceEntry>()
        val typeCounts = mutableMapOf<String, Int>()

        // 1. Parse values resources (strings.xml, colors.xml, etc.)
        val valuesDir = File(resDir, "values")
        if (valuesDir.exists()) {
            valuesDir.listFiles()?.filter { it.extension == "xml" }?.forEach { file ->
                parseValuesFile(file, resources, typeCounts)
            }
        }

        // 2. Collect file-based resources (layouts, drawables, menus, etc.)
        collectFileResources(resDir, "layout", TYPE_ID_LAYOUT, resources, typeCounts)
        collectFileResources(resDir, "drawable", TYPE_ID_DRAWABLE, resources, typeCounts)
        collectFileResources(resDir, "mipmap-hdpi", TYPE_ID_MIPMAP, resources, typeCounts)
        collectFileResources(resDir, "mipmap-xhdpi", TYPE_ID_MIPMAP, resources, typeCounts)
        collectFileResources(resDir, "mipmap-xxhdpi", TYPE_ID_MIPMAP, resources, typeCounts)
        collectFileResources(resDir, "mipmap-anydpi-v26", TYPE_ID_MIPMAP, resources, typeCounts)
        collectFileResources(resDir, "menu", TYPE_ID_MENU, resources, typeCounts)
        collectFileResources(resDir, "xml", TYPE_ID_XML, resources, typeCounts)
        collectFileResources(resDir, "anim", TYPE_ID_ANIM, resources, typeCounts)

        // 3. Collect IDs from layouts (@+id/...)
        collectLayoutIds(resDir, resources, typeCounts)

        // Build resource ID map: type -> name -> 0x7fTTEEEE
        val resourceIdMap = mutableMapOf<String, MutableMap<String, Int>>()
        resources.forEach { entry ->
            val id = (APP_PACKAGE_ID shl 24) or (entry.typeId shl 16) or entry.entryId
            resourceIdMap.getOrPut(entry.type) { mutableMapOf() }[entry.name] = id
        }

        // 4. Build resources.arsc
        val arscBytes = buildResourcesArsc(resources, packageName)

        // 5. Package into resources.ap_
        ZipOutputStream(FileOutputStream(outputApk)).use { zos ->
            // AndroidManifest.xml → binary XML
            val manifestXml = manifestFile.readText()
            val binaryManifest = xmlEncoder.encode(manifestXml)
            if (binaryManifest != null) {
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zos.write(binaryManifest)
                zos.closeEntry()
            } else {
                // Fallback: include raw manifest
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zos.write(manifestFile.readBytes())
                zos.closeEntry()
            }

            // resources.arsc
            zos.putNextEntry(ZipEntry("resources.arsc"))
            zos.write(arscBytes)
            zos.closeEntry()

            // XML resources → binary XML
            val xmlDirs = listOf("layout", "menu", "xml", "anim", "drawable")
            xmlDirs.forEach { dirName ->
                val dir = File(resDir, dirName)
                if (dir.exists()) {
                    dir.listFiles()?.filter { it.extension == "xml" }?.forEach { file ->
                        val binaryXml = xmlEncoder.encode(file.readText())
                        val entryName = "res/$dirName/${file.name}"
                        zos.putNextEntry(ZipEntry(entryName))
                        if (binaryXml != null) {
                            zos.write(binaryXml)
                        } else {
                            zos.write(file.readBytes())
                        }
                        zos.closeEntry()
                    }
                }
            }

            // Non-XML resources (images, etc.) → copy as-is
            val binaryDirs = listOf("drawable", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi",
                "mipmap-anydpi-v26")
            binaryDirs.forEach { dirName ->
                val dir = File(resDir, dirName)
                if (dir.exists()) {
                    dir.listFiles()?.filter { it.extension != "xml" || dirName.startsWith("mipmap") }?.forEach { file ->
                        if (file.extension != "xml" || !xmlDirs.contains(dirName)) {
                            val entryName = "res/$dirName/${file.name}"
                            try {
                                zos.putNextEntry(ZipEntry(entryName))
                                zos.write(file.readBytes())
                                zos.closeEntry()
                            } catch (_: Exception) { /* skip duplicate entries */ }
                        }
                    }
                }
            }

            // values/ files are already compiled into resources.arsc, no need to include raw XML
        }

        Log.d(TAG, "Compiled ${resources.size} resources into ${outputApk.name}")
        return resourceIdMap
    }

    /**
     * Generate R.java file with all resource ID constants.
     */
    fun generateRJava(resourceIds: Map<String, Map<String, Int>>, packageName: String, outputDir: File) {
        val packagePath = packageName.replace('.', '/')
        val rFile = File(outputDir, "$packagePath/R.java")
        rFile.parentFile?.mkdirs()

        val sb = StringBuilder()
        sb.appendLine("package $packageName;")
        sb.appendLine()
        sb.appendLine("public final class R {")

        resourceIds.forEach { (typeName, entries) ->
            sb.appendLine("    public static final class $typeName {")
            entries.forEach { (name, id) ->
                sb.appendLine("        public static final int ${sanitizeName(name)} = 0x${id.toString(16)};")
            }
            sb.appendLine("    }")
        }

        sb.appendLine("}")
        rFile.writeText(sb.toString())
        Log.d(TAG, "Generated R.java with ${resourceIds.values.sumOf { it.size }} entries")
    }

    // ─────────────────────────────────────────────────────────────────
    // Parsing
    // ─────────────────────────────────────────────────────────────────

    private fun parseValuesFile(file: File, resources: MutableList<ResourceEntry>, typeCounts: MutableMap<String, Int>) {
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val doc = factory.newDocumentBuilder().parse(file)
            val root = doc.documentElement
            val children = root.childNodes

            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                val element = node as org.w3c.dom.Element

                when (element.tagName) {
                    "string" -> addValueResource("string", TYPE_ID_STRING, element, resources, typeCounts)
                    "color" -> addValueResource("color", TYPE_ID_COLOR, element, resources, typeCounts)
                    "dimen" -> addValueResource("dimen", TYPE_ID_DIMEN, element, resources, typeCounts)
                    "bool" -> addValueResource("bool", TYPE_ID_BOOL, element, resources, typeCounts)
                    "integer" -> addValueResource("integer", TYPE_ID_INTEGER, element, resources, typeCounts)
                    "style" -> addValueResource("style", TYPE_ID_STYLE, element, resources, typeCounts)
                    "declare-styleable", "attr" -> { /* skip for now */ }
                    "item" -> {
                        val type = element.getAttribute("type")
                        if (type == "id") {
                            addIdResource(element.getAttribute("name"), resources, typeCounts)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse values file ${file.name}: ${e.message}")
        }
    }

    private fun addValueResource(
        type: String, typeId: Int,
        element: org.w3c.dom.Element,
        resources: MutableList<ResourceEntry>,
        typeCounts: MutableMap<String, Int>
    ) {
        val name = element.getAttribute("name") ?: return
        val value = element.textContent?.trim() ?: ""
        val entryId = typeCounts.getOrDefault(type, 0)
        typeCounts[type] = entryId + 1
        resources.add(ResourceEntry(type, name, value, typeId, entryId))
    }

    private fun addIdResource(name: String, resources: MutableList<ResourceEntry>, typeCounts: MutableMap<String, Int>) {
        // Check if ID already exists
        if (resources.any { it.type == "id" && it.name == name }) return
        val entryId = typeCounts.getOrDefault("id", 0)
        typeCounts["id"] = entryId + 1
        resources.add(ResourceEntry("id", name, null, TYPE_ID_ID, entryId))
    }

    private fun collectFileResources(
        resDir: File, dirName: String, typeId: Int,
        resources: MutableList<ResourceEntry>,
        typeCounts: MutableMap<String, Int>
    ) {
        val dir = File(resDir, dirName)
        if (!dir.exists()) return

        // Determine type name from directory name (strip qualifier suffix)
        val typeName = dirName.substringBefore('-')
            .let { if (it == "mipmap") "mipmap" else it }

        dir.listFiles()?.forEach { file ->
            val name = file.nameWithoutExtension
            // Avoid duplicates (e.g., same resource in multiple density buckets)
            if (resources.any { it.type == typeName && it.name == name }) return@forEach

            val entryId = typeCounts.getOrDefault(typeName, 0)
            typeCounts[typeName] = entryId + 1
            resources.add(ResourceEntry(typeName, name, "res/$dirName/${file.name}", typeId, entryId))
        }
    }

    private fun collectLayoutIds(resDir: File, resources: MutableList<ResourceEntry>, typeCounts: MutableMap<String, Int>) {
        val layoutDir = File(resDir, "layout")
        if (!layoutDir.exists()) return

        layoutDir.listFiles()?.filter { it.extension == "xml" }?.forEach { file ->
            try {
                val content = file.readText()
                // Find @+id/name patterns
                val idPattern = Regex("@\\+id/(\\w+)")
                idPattern.findAll(content).forEach { match ->
                    addIdResource(match.groupValues[1], resources, typeCounts)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to scan IDs in ${file.name}: ${e.message}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // resources.arsc builder — simplified version
    // ─────────────────────────────────────────────────────────────────

    /**
     * Build a minimal but valid resources.arsc.
     * This produces a resource table with a single default configuration containing
     * all value resources as string entries.
     */
    private fun buildResourcesArsc(resources: List<ResourceEntry>, packageName: String): ByteArray {
        // Group resources by type
        val byType = resources.groupBy { it.type }
            .toSortedMap(compareBy { getTypeId(it) })

        // Collect all strings for the global string pool
        val globalStrings = mutableListOf<String>()
        val globalStringIndex = mutableMapOf<String, Int>()

        fun addGlobalString(s: String): Int {
            return globalStringIndex.getOrPut(s) {
                globalStrings.add(s)
                globalStrings.size - 1
            }
        }

        // Pre-populate with resource values and file paths
        resources.forEach { entry ->
            entry.value?.let { addGlobalString(it) }
        }

        // Build key string pool (resource names)
        val keyStrings = mutableListOf<String>()
        val keyStringIndex = mutableMapOf<String, Int>()
        resources.forEach { entry ->
            if (entry.name !in keyStringIndex) {
                keyStringIndex[entry.name] = keyStrings.size
                keyStrings.add(entry.name)
            }
        }

        // Build type string pool
        val typeStrings = mutableListOf<String>()
        val typeStringIndex = mutableMapOf<String, Int>()
        byType.keys.forEach { type ->
            if (type !in typeStringIndex) {
                typeStringIndex[type] = typeStrings.size
                typeStrings.add(type)
            }
        }

        // Build the package chunk content (typeSpec + type chunks for each resource type)
        val packageChunkBody = ByteArrayOutputStream()

        // Type string pool
        val typeStringPoolBytes = buildUtf8StringPool(typeStrings)
        packageChunkBody.write(typeStringPoolBytes)

        // Key string pool
        val keyStringPoolBytes = buildUtf8StringPool(keyStrings)
        packageChunkBody.write(keyStringPoolBytes)

        // For each type: typeSpec chunk + type chunk
        byType.forEach { (typeName, entries) ->
            val typeId = getTypeId(typeName)
            val entryCount = entries.maxOf { it.entryId } + 1

            // TypeSpec chunk (flags for each entry - 0 = no special config)
            val specChunkSize = 8 + 4 + entryCount * 4
            val specBuf = ByteBuffer.allocate(specChunkSize).order(ByteOrder.LITTLE_ENDIAN)
            specBuf.putShort(RES_TABLE_TYPE_SPEC_TYPE)
            specBuf.putShort(8.toShort()) // header size
            specBuf.putInt(specChunkSize)
            specBuf.put(typeId.toByte()) // id
            specBuf.put(0.toByte()) // res0
            specBuf.putShort(0.toShort()) // res1
            repeat(entryCount) { specBuf.putInt(0) } // flags: default config
            packageChunkBody.write(specBuf.array())

            // Type chunk (actual entries with values)
            // Config size = 64 bytes (default config, all zeroes)
            val configSize = 64
            val entryDataSize = calculateEntryDataSize(entries, entryCount)
            val headerSize = 12 + configSize
            val offsetsSize = entryCount * 4
            val typeChunkSize = headerSize + offsetsSize + entryDataSize

            val typeBuf = ByteBuffer.allocate(typeChunkSize).order(ByteOrder.LITTLE_ENDIAN)
            typeBuf.putShort(RES_TABLE_TYPE_TYPE)
            typeBuf.putShort(headerSize.toShort())
            typeBuf.putInt(typeChunkSize)
            typeBuf.put(typeId.toByte()) // id
            typeBuf.put(0.toByte()) // res0
            typeBuf.putShort(0.toShort()) // res1
            typeBuf.putInt(entryCount) // entry count
            typeBuf.putInt(headerSize + offsetsSize) // entries start
            // Config (64 bytes of zeros = default config)
            typeBuf.putInt(configSize) // config size
            repeat((configSize - 4) / 4) { typeBuf.putInt(0) }

            // Entry offsets
            val entryMap = entries.associateBy { it.entryId }
            var offset = 0
            for (i in 0 until entryCount) {
                if (entryMap.containsKey(i)) {
                    typeBuf.putInt(offset)
                    offset += 16 // entry header (8) + value (8)
                } else {
                    typeBuf.putInt(-1) // NO_ENTRY
                }
            }

            // Entry data
            for (i in 0 until entryCount) {
                val entry = entryMap[i] ?: continue
                val keyIdx = keyStringIndex[entry.name] ?: 0

                // Entry header
                typeBuf.putShort(8.toShort()) // size
                typeBuf.putShort(0.toShort()) // flags
                typeBuf.putInt(keyIdx) // key string index

                // Value
                typeBuf.putShort(8.toShort()) // value size
                typeBuf.put(0.toByte()) // res0
                val (valueType, valueData) = resolveResourceValue(entry, globalStringIndex)
                typeBuf.put(valueType.toByte()) // type
                typeBuf.putInt(valueData) // data
            }

            packageChunkBody.write(typeBuf.array())
        }

        // Now build the complete package chunk
        val packageHeaderSize = 288 // standard package header
        val packageBody = packageChunkBody.toByteArray()
        val packageChunkSize = packageHeaderSize + packageBody.size

        val packageBuf = ByteBuffer.allocate(packageChunkSize).order(ByteOrder.LITTLE_ENDIAN)
        packageBuf.putShort(RES_TABLE_PACKAGE_TYPE)
        packageBuf.putShort(packageHeaderSize.toShort())
        packageBuf.putInt(packageChunkSize)
        packageBuf.putInt(APP_PACKAGE_ID) // id

        // Package name (128 UTF-16 chars)
        val nameBytes = packageName.toByteArray(Charsets.UTF_16LE)
        val nameBuffer = ByteArray(256)
        System.arraycopy(nameBytes, 0, nameBuffer, 0, minOf(nameBytes.size, 254))
        packageBuf.put(nameBuffer)

        // Offsets to type strings, last public type, key strings, last public key
        packageBuf.putInt(packageHeaderSize) // type strings offset (from package start)
        packageBuf.putInt(typeStrings.size) // last public type
        packageBuf.putInt(packageHeaderSize + typeStringPoolBytes.size) // key strings offset
        packageBuf.putInt(keyStrings.size) // last public key

        // Pad header to 288 bytes
        val remaining = packageHeaderSize - packageBuf.position()
        repeat(remaining) { packageBuf.put(0.toByte()) }

        packageBuf.put(packageBody)

        // Build global string pool
        val globalStringPoolBytes = buildUtf8StringPool(globalStrings)

        // Build final resources.arsc
        val totalSize = 12 + globalStringPoolBytes.size + packageBuf.array().size
        val result = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        result.putShort(RES_TABLE_TYPE)
        result.putShort(12.toShort()) // header size
        result.putInt(totalSize)
        result.putInt(1) // package count

        result.put(globalStringPoolBytes)
        result.put(packageBuf.array())

        return result.array()
    }

    private fun calculateEntryDataSize(entries: List<ResourceEntry>, entryCount: Int): Int {
        return entries.size * 16 // each present entry = 8 (header) + 8 (value)
    }

    private fun resolveResourceValue(entry: ResourceEntry, globalStringIndex: Map<String, Int>): Pair<Int, Int> {
        val value = entry.value ?: return 0x00 to 0 // TYPE_NULL

        // File reference (res/layout/..., res/drawable/..., etc.)
        if (value.startsWith("res/")) {
            val idx = globalStringIndex[value] ?: 0
            return 0x03 to idx // TYPE_STRING (reference to string pool)
        }

        // Color value
        if (value.startsWith("#")) {
            try {
                val colorStr = when (value.length) {
                    7 -> "FF${value.substring(1)}" // #RRGGBB → FFRRGGBB
                    9 -> value.substring(1) // #AARRGGBB
                    4 -> { // #RGB → FFRRGGBB
                        val r = value[1]; val g = value[2]; val b = value[3]
                        "FF$r$r$g$g$b$b"
                    }
                    else -> null
                }
                if (colorStr != null) {
                    return 0x1C to colorStr.toLong(16).toInt() // TYPE_INT_COLOR_ARGB8
                }
            } catch (_: Exception) {}
        }

        // Boolean
        if (value == "true") return 0x12 to -1
        if (value == "false") return 0x12 to 0

        // Integer
        try {
            return 0x10 to value.toInt()
        } catch (_: Exception) {}

        // Default: string reference
        val idx = globalStringIndex[value] ?: 0
        return 0x03 to idx // TYPE_STRING
    }

    private fun getTypeId(typeName: String): Int {
        return when (typeName) {
            "attr" -> TYPE_ID_ATTR
            "drawable" -> TYPE_ID_DRAWABLE
            "mipmap" -> TYPE_ID_MIPMAP
            "layout" -> TYPE_ID_LAYOUT
            "anim" -> TYPE_ID_ANIM
            "string" -> TYPE_ID_STRING
            "color" -> TYPE_ID_COLOR
            "dimen" -> TYPE_ID_DIMEN
            "style" -> TYPE_ID_STYLE
            "id" -> TYPE_ID_ID
            "bool" -> TYPE_ID_BOOL
            "integer" -> TYPE_ID_INTEGER
            "array" -> TYPE_ID_ARRAY
            "xml" -> TYPE_ID_XML
            "menu" -> TYPE_ID_MENU
            else -> TYPE_ID_XML + 1 // unknown type
        }
    }

    private fun sanitizeName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_]"), "_")
    }

    // ─────────────────────────────────────────────────────────────────
    // UTF-8 String Pool builder
    // ─────────────────────────────────────────────────────────────────

    private fun buildUtf8StringPool(strings: List<String>): ByteArray {
        if (strings.isEmpty()) {
            // Empty string pool: just header
            val buf = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            buf.putShort(RES_STRING_POOL_TYPE)
            buf.putShort(28.toShort())
            buf.putInt(28)
            buf.putInt(0) // string count
            buf.putInt(0) // style count
            buf.putInt(0x100) // UTF-8
            buf.putInt(0) // strings start
            buf.putInt(0) // styles start
            return buf.array()
        }

        val headerSize = 28
        val encodedStrings = strings.map { it.toByteArray(Charsets.UTF_8) }

        // Calculate offsets
        val offsets = mutableListOf<Int>()
        var currentOffset = 0
        encodedStrings.forEach { encoded ->
            offsets.add(currentOffset)
            val charLen = encoded.toString(Charsets.UTF_8).length
            // Each string: charLen (1 byte) + byteLen (1 byte) + data + null
            currentOffset += 1 + 1 + encoded.size + 1
        }

        val stringsDataSize = currentOffset
        val paddedDataSize = (stringsDataSize + 3) and 3.inv()
        val offsetsSize = strings.size * 4
        val stringsStart = headerSize + offsetsSize
        val totalSize = stringsStart + paddedDataSize

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_STRING_POOL_TYPE)
        buf.putShort(headerSize.toShort())
        buf.putInt(totalSize)
        buf.putInt(strings.size) // string count
        buf.putInt(0) // style count
        buf.putInt(0x100) // flags: UTF-8
        buf.putInt(stringsStart) // strings start
        buf.putInt(0) // styles start

        // Offsets
        offsets.forEach { buf.putInt(it) }

        // String data
        encodedStrings.forEach { encoded ->
            val charLen = encoded.toString(Charsets.UTF_8).length
            buf.put(charLen.toByte())
            buf.put(encoded.size.toByte())
            buf.put(encoded)
            buf.put(0.toByte())
        }

        // Pad
        while (buf.position() < totalSize) buf.put(0.toByte())

        return buf.array()
    }
}

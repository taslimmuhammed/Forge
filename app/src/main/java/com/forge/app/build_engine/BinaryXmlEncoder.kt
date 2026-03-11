package com.forge.app.build_engine

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Encodes plain-text Android XML (layouts, manifest) into the Android Binary XML (AXML) format.
 *
 * Android's runtime AssetManager cannot parse plain-text XML — it requires the binary "chunk" format
 * that aapt2 normally produces. This encoder handles the most common subset:
 * - Elements with attributes
 * - Text content
 * - Namespace declarations (android:)
 *
 * Limitations:
 * - Does not handle resource references (@string/foo, @color/bar) with proper resource IDs
 * - Style attributes are passed as raw strings
 * - Complex XPath-style selectors are not supported
 */
class BinaryXmlEncoder {

    companion object {
        private const val TAG = "BinaryXmlEncoder"

        // Chunk types
        private const val CHUNK_AXML_FILE = 0x0003  // RES_XML_TYPE
        private const val CHUNK_STRING_POOL = 0x0001  // RES_STRING_POOL_TYPE
        private const val CHUNK_RESOURCE_MAP = 0x0180  // RES_XML_RESOURCE_MAP_TYPE
        private const val CHUNK_START_NAMESPACE = 0x0100  // RES_XML_START_NAMESPACE_TYPE
        private const val CHUNK_END_NAMESPACE = 0x0101  // RES_XML_END_NAMESPACE_TYPE
        private const val CHUNK_START_ELEMENT = 0x0102  // RES_XML_START_ELEMENT_TYPE
        private const val CHUNK_END_ELEMENT = 0x0103  // RES_XML_END_ELEMENT_TYPE
        private const val CHUNK_TEXT = 0x0104  // RES_XML_CDATA_TYPE

        // Android namespace
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        // Attribute types
        private const val TYPE_NULL = 0x00
        private const val TYPE_STRING = 0x03
        private const val TYPE_INT_DEC = 0x10
        private const val TYPE_INT_HEX = 0x11
        private const val TYPE_INT_BOOLEAN = 0x12
        private const val TYPE_DIMENSION = 0x05
        private const val TYPE_FRACTION = 0x06

        // Common android: attribute resource IDs
        private val ANDROID_ATTR_IDS = mapOf(
            "theme" to 0x01010000,
            "label" to 0x01010001,
            "icon" to 0x01010002,
            "name" to 0x01010003,
            "permission" to 0x01010006,
            "protectionLevel" to 0x01010009,
            "text" to 0x01010014,
            "textColor" to 0x01010098,
            "textSize" to 0x01010095,
            "textStyle" to 0x01010097,
            "background" to 0x010100d4,
            "layout_width" to 0x010100f4,
            "layout_height" to 0x010100f5,
            "id" to 0x010100d0,
            "gravity" to 0x010100af,
            "orientation" to 0x010100c4,
            "padding" to 0x010100d5,
            "paddingLeft" to 0x010100d6,
            "paddingTop" to 0x010100d7,
            "paddingRight" to 0x010100d8,
            "paddingBottom" to 0x010100d9,
            "layout_margin" to 0x010100f6,
            "layout_marginLeft" to 0x010100f7,
            "layout_marginTop" to 0x010100f8,
            "layout_marginRight" to 0x010100f9,
            "layout_marginBottom" to 0x010100fa,
            "layout_gravity" to 0x010100b3,
            "layout_weight" to 0x01010181,
            "visibility" to 0x010100dc,
            "enabled" to 0x0101000e,
            "clickable" to 0x010100e5,
            "hint" to 0x01010150,
            "inputType" to 0x01010220,
            "maxLines" to 0x01010153,
            "minLines" to 0x01010154,
            "maxWidth" to 0x0101011f,
            "maxHeight" to 0x01010120,
            "src" to 0x01010119,
            "scaleType" to 0x0101011a,
            "adjustViewBounds" to 0x0101011b,
            "contentDescription" to 0x0101013f,
            "allowBackup" to 0x01010280,
            "supportsRtl" to 0x010103af,
            "roundIcon" to 0x0101052c,
            "exported" to 0x01010010,
            "configChanges" to 0x0101001f,
            "screenOrientation" to 0x0101001e,
            "launchMode" to 0x0101001d,
            "windowSoftInputMode" to 0x0101022b,
            "parentActivityName" to 0x010103a7,
            "versionCode" to 0x0101021b,
            "versionName" to 0x0101021c,
            "minSdkVersion" to 0x0101020c,
            "targetSdkVersion" to 0x01010270,
            "compileSdkVersion" to 0x01010572,
            "package" to 0x0,  // special — handled by manifest parser
            "foregroundServiceType" to 0x01010599,
            "grantUriPermissions" to 0x0101001b,
            "authorities" to 0x01010018,
            "resource" to 0x01010025,
            "value" to 0x01010024,
            "scheme" to 0x01010027,
            "action" to 0x0, // intent-filter child
            "category" to 0x0, // intent-filter child
            "data" to 0x0,
            "requestLegacyExternalStorage" to 0x01010569,
            "usesCleartextTraffic" to 0x010104ec,
            "hardwareAccelerated" to 0x010102d3,
            "largeHeap" to 0x0101035a,
            "lineSpacingMultiplier" to 0x01010218,
            "singleLine" to 0x01010151,
            "ellipsize" to 0x010100ab,
            "maxSdkVersion" to 0x01010271,
            "required" to 0x0101028e,
            "layout_centerInParent" to 0x0101017f,
            "layout_centerHorizontal" to 0x01010180,
            "layout_centerVertical" to 0x01010181,
            "layout_alignParentTop" to 0x0101017c,
            "layout_alignParentBottom" to 0x0101017d,
            "layout_alignParentLeft" to 0x0101017a,
            "layout_alignParentRight" to 0x0101017b,
        )

        // Dimension unit mapping
        private val DIMENSION_UNITS = mapOf(
            "px" to 0, "dip" to 1, "dp" to 1, "sp" to 2,
            "pt" to 3, "in" to 4, "mm" to 5
        )

        // layout_width / layout_height special values
        private val LAYOUT_SIZE_SPECIAL = mapOf(
            "match_parent" to -1, "fill_parent" to -1, "wrap_content" to -2
        )
    }

    /**
     * Encode a plain XML string into Android binary XML format.
     * @param xmlContent The plain-text XML content
     * @return The binary XML bytes, or null if encoding fails
     */
    fun encode(xmlContent: String): ByteArray? {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }
            val doc = factory.newDocumentBuilder().parse(xmlContent.byteInputStream())
            val root = doc.documentElement

            // Collect all strings and resource IDs
            val strings = mutableListOf<String>()
            val stringIndex = mutableMapOf<String, Int>()
            val resourceIds = mutableListOf<Int>()

            fun addString(s: String): Int {
                return stringIndex.getOrPut(s) {
                    strings.add(s)
                    strings.size - 1
                }
            }

            // Pre-populate namespace strings
            val nsPrefix = addString("android")
            val nsUri = addString(ANDROID_NS)

            // Collect strings from the DOM tree
            collectStrings(root, ::addString)

            // Build the binary chunks
            val body = ByteArrayOutputStream()

            // 1. String pool chunk
            val stringPoolBytes = buildStringPool(strings)
            body.write(stringPoolBytes)

            // 2. Resource ID map (maps string pool indices → android resource IDs)
            val resMapBytes = buildResourceIdMap(strings, resourceIds)
            if (resMapBytes.isNotEmpty()) body.write(resMapBytes)

            // 3. Start namespace
            val startNsBytes = buildNamespaceChunk(CHUNK_START_NAMESPACE, nsPrefix, nsUri, 0)
            body.write(startNsBytes)

            // 4. Elements (recursive)
            writeElement(root, body, stringIndex, nsPrefix, nsUri, 0)

            // 5. End namespace
            val endNsBytes = buildNamespaceChunk(CHUNK_END_NAMESPACE, nsPrefix, nsUri, 0)
            body.write(endNsBytes)

            // Wrap in AXML file header
            val totalSize = 8 + body.size()
            val result = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
            result.putShort(CHUNK_AXML_FILE.toShort())
            result.putShort(8.toShort()) // header size
            result.putInt(totalSize)
            result.put(body.toByteArray())

            result.array()
        } catch (e: Exception) {
            Log.e(TAG, "Binary XML encoding failed", e)
            null
        }
    }

    private fun collectStrings(element: org.w3c.dom.Element, addString: (String) -> Int) {
        // Element name
        addString(element.localName ?: element.tagName)

        // Namespace URI if present
        element.namespaceURI?.let { addString(it) }

        // Attributes
        val attrs = element.attributes
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i)
            val prefix = attr.prefix ?: ""
            val localName = attr.localName ?: attr.nodeName
            if (prefix == "xmlns") continue // namespace declarations handled separately
            if (localName == "xmlns") continue

            addString(localName)
            addString(attr.nodeValue)
            if (prefix.isNotEmpty()) addString(prefix)
            attr.namespaceURI?.let { addString(it) }
        }

        // Child elements
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            when (child.nodeType) {
                org.w3c.dom.Node.ELEMENT_NODE -> collectStrings(child as org.w3c.dom.Element, addString)
                org.w3c.dom.Node.TEXT_NODE -> {
                    val text = child.textContent?.trim()
                    if (!text.isNullOrEmpty()) addString(text)
                }
            }
        }
    }

    private fun buildStringPool(strings: List<String>): ByteArray {
        val stringCount = strings.size

        data class EncodedString(val charLen: ByteArray, val byteLen: ByteArray, val bytes: ByteArray)
        val encodedStrings = strings.map { text ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            EncodedString(
                charLen = encodeLengthUtf8(text.length),
                byteLen = encodeLengthUtf8(bytes.size),
                bytes = bytes
            )
        }

        // Compute per-string offsets based on the exact bytes that will be written.
        val offsets = mutableListOf<Int>()
        var currentOffset = 0
        encodedStrings.forEach { encoded ->
            offsets.add(currentOffset)
            currentOffset += encoded.charLen.size + encoded.byteLen.size + encoded.bytes.size + 1
        }

        val stringsDataSize = currentOffset
        val paddedStringsDataSize = (stringsDataSize + 3) and 3.inv() // 4-byte alignment

        val headerSize = 28
        val totalSize = headerSize + (4 * stringCount) + paddedStringsDataSize

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(CHUNK_STRING_POOL.toShort()) // type
        buf.putShort(headerSize.toShort()) // header size
        buf.putInt(totalSize) // total size
        buf.putInt(stringCount) // string count
        buf.putInt(0) // style count
        buf.putInt(0x100) // flags: UTF-8
        buf.putInt(headerSize + 4 * stringCount) // strings start offset
        buf.putInt(0) // styles start offset

        // String offsets
        offsets.forEach { buf.putInt(it) }

        // String data
        encodedStrings.forEach { encoded ->
            buf.put(encoded.charLen)
            buf.put(encoded.byteLen)
            buf.put(encoded.bytes)
            buf.put(0.toByte()) // null terminator
        }

        // Pad remaining
        while (buf.position() < totalSize) buf.put(0.toByte())

        return buf.array()
    }

    private fun encodeLengthUtf8(length: Int): ByteArray {
        return if (length <= 0x7F) {
            byteArrayOf(length.toByte())
        } else {
            byteArrayOf(
                ((length shr 8) or 0x80).toByte(),
                (length and 0xFF).toByte()
            )
        }
    }

    private fun buildResourceIdMap(strings: List<String>, outIds: MutableList<Int>): ByteArray {
        // Map string indices to android resource IDs for known attribute names
        val ids = mutableListOf<Pair<Int, Int>>() // (index, resId)
        var maxIndex = -1
        strings.forEachIndexed { index, str ->
            val resId = ANDROID_ATTR_IDS[str]
            if (resId != null && resId != 0) {
                ids.add(index to resId)
                if (index > maxIndex) maxIndex = index
            }
        }
        if (ids.isEmpty()) return ByteArray(0)

        // Resource ID map must contain IDs for contiguous indices from 0..maxIndex
        val idArray = IntArray(maxIndex + 1)
        ids.forEach { (idx, resId) -> idArray[idx] = resId }

        val headerSize = 8
        val totalSize = headerSize + idArray.size * 4
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(CHUNK_RESOURCE_MAP.toShort())
        buf.putShort(headerSize.toShort())
        buf.putInt(totalSize)
        idArray.forEach { buf.putInt(it) }

        outIds.addAll(idArray.toList())
        return buf.array()
    }

    private fun buildNamespaceChunk(type: Int, prefixIdx: Int, uriIdx: Int, lineNumber: Int): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(type.toShort())
        buf.putShort(16.toShort()) // header size
        buf.putInt(24) // total size
        buf.putInt(lineNumber) // line number
        buf.putInt(-1) // comment (none)
        buf.putInt(prefixIdx) // prefix string index
        buf.putInt(uriIdx) // uri string index
        return buf.array()
    }

    private fun writeElement(
        element: org.w3c.dom.Element,
        out: ByteArrayOutputStream,
        stringIndex: Map<String, Int>,
        nsPrefixIdx: Int,
        nsUriIdx: Int,
        lineNumber: Int
    ) {
        val elementName = element.localName ?: element.tagName
        val elementNameIdx = stringIndex[elementName] ?: -1

        // Collect non-xmlns attributes
        val attrs = mutableListOf<AttrData>()
        val attrNodes = element.attributes
        for (i in 0 until attrNodes.length) {
            val attr = attrNodes.item(i)
            val prefix = attr.prefix ?: ""
            val localName = attr.localName ?: attr.nodeName
            if (prefix == "xmlns" || localName == "xmlns") continue

            val nsIdx = if (attr.namespaceURI == ANDROID_NS) nsUriIdx else -1
            val nameIdx = stringIndex[localName] ?: continue
            val valueStr = attr.nodeValue
            val valueIdx = stringIndex[valueStr] ?: -1

            val (type, data) = resolveAttributeValue(localName, valueStr, stringIndex)
            attrs.add(AttrData(nsIdx, nameIdx, valueIdx, type, data))
        }

        // Sort: android-namespace attrs first (by name index), then others
        attrs.sortWith(compareBy({ if (it.nsIdx == nsUriIdx) 0 else 1 }, { it.nameIdx }))

        // Start element chunk
        val attrCount = attrs.size
        val headerSize = 16
        val attrStart = 20 // offset from chunk start to attribute data
        val attrSize = 20 // bytes per attribute
        val chunkSize = 36 + attrCount * attrSize

        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(CHUNK_START_ELEMENT.toShort())
        buf.putShort(headerSize.toShort())
        buf.putInt(chunkSize)
        buf.putInt(lineNumber) // line number
        buf.putInt(-1) // comment
        buf.putInt(-1) // namespace (element namespace, -1 = default)
        buf.putInt(elementNameIdx) // name
        buf.putShort(0x14.toShort()) // attribute start (20 bytes from start of attrs section)
        buf.putShort(attrSize.toShort()) // attribute size
        buf.putShort(attrCount.toShort()) // attribute count
        buf.putShort(0.toShort()) // id index
        buf.putShort(0.toShort()) // class index
        buf.putShort(0.toShort()) // style index

        // Attributes
        attrs.forEach { attr ->
            buf.putInt(attr.nsIdx) // namespace
            buf.putInt(attr.nameIdx) // name
            buf.putInt(attr.valueIdx) // raw value (string index, or -1 for typed values)
            buf.putShort(8.toShort()) // size
            buf.put(0.toByte()) // res0
            buf.put(attr.type.toByte()) // type
            buf.putInt(attr.data) // data
        }

        out.write(buf.array())

        // Child elements
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            when (child.nodeType) {
                org.w3c.dom.Node.ELEMENT_NODE ->
                    writeElement(child as org.w3c.dom.Element, out, stringIndex, nsPrefixIdx, nsUriIdx, lineNumber + i)
                org.w3c.dom.Node.TEXT_NODE -> {
                    val text = child.textContent?.trim()
                    if (!text.isNullOrEmpty()) {
                        writeCData(text, out, stringIndex, lineNumber + i)
                    }
                }
            }
        }

        // End element chunk
        val endBuf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        endBuf.putShort(CHUNK_END_ELEMENT.toShort())
        endBuf.putShort(headerSize.toShort())
        endBuf.putInt(24)
        endBuf.putInt(lineNumber) // line number
        endBuf.putInt(-1) // comment
        endBuf.putInt(-1) // namespace
        endBuf.putInt(elementNameIdx) // name
        out.write(endBuf.array())
    }

    private fun writeCData(text: String, out: ByteArrayOutputStream, stringIndex: Map<String, Int>, lineNumber: Int) {
        val textIdx = stringIndex[text] ?: return
        val buf = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(CHUNK_TEXT.toShort())
        buf.putShort(16.toShort())
        buf.putInt(28)
        buf.putInt(lineNumber)
        buf.putInt(-1) // comment
        buf.putInt(textIdx) // data index
        buf.putShort(8.toShort()) // size
        buf.put(0.toByte()) // res0
        buf.put(TYPE_NULL.toByte()) // type
        buf.putInt(0) // data
        out.write(buf.array())
    }

    /**
     * Resolve an attribute value to its binary type and data.
     * Handles dimension values (dp, sp), booleans, integers, hex colors, and special constants.
     */
    private fun resolveAttributeValue(attrName: String, value: String, stringIndex: Map<String, Int>): Pair<Int, Int> {
        // Special layout_width / layout_height values
        if (attrName == "layout_width" || attrName == "layout_height") {
            LAYOUT_SIZE_SPECIAL[value]?.let { return TYPE_INT_DEC to it }
        }

        // Boolean values
        if (value == "true") return TYPE_INT_BOOLEAN to -1
        if (value == "false") return TYPE_INT_BOOLEAN to 0

        // Hex color (#AARRGGBB or #RRGGBB)
        if (value.startsWith("#") && value.length in listOf(4, 5, 7, 9)) {
            try {
                val colorStr = if (value.length == 7) "FF${value.substring(1)}" else if (value.length == 9) value.substring(1) else value.substring(1)
                return TYPE_INT_HEX to colorStr.toLong(16).toInt()
            } catch (_: Exception) {}
        }

        // Dimension values (16dp, 14sp, etc.)
        val dimMatch = Regex("^(\\d+(?:\\.\\d+)?)(dp|dip|sp|pt|in|mm|px)$").find(value)
        if (dimMatch != null) {
            val amount = dimMatch.groupValues[1].toFloat()
            val unit = DIMENSION_UNITS[dimMatch.groupValues[2]] ?: 0
            // Android dimension encoding: (value << 8) | (unit << 4) | COMPLEX_UNIT
            val intPart = (amount * 256).toInt()
            return TYPE_DIMENSION to ((intPart shl 8) or (unit shl 4) or 0x01)
        }

        // Plain integer
        try {
            return TYPE_INT_DEC to value.toInt()
        } catch (_: Exception) {}

        // Hex integer (0xNNN)
        if (value.startsWith("0x")) {
            try {
                return TYPE_INT_HEX to value.substring(2).toLong(16).toInt()
            } catch (_: Exception) {}
        }

        // Default: string value
        val strIdx = stringIndex[value] ?: -1
        return TYPE_STRING to strIdx
    }

    private data class AttrData(
        val nsIdx: Int,
        val nameIdx: Int,
        val valueIdx: Int,
        val type: Int,
        val data: Int
    )
}

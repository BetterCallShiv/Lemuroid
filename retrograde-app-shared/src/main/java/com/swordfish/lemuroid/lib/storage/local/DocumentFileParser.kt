package com.swordfish.lemuroid.lib.storage.local

import android.content.Context
import com.swordfish.lemuroid.common.kotlin.calculateCrc32
import com.swordfish.lemuroid.common.kotlin.toStringCRC32
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.storage.BaseStorageFile
import com.swordfish.lemuroid.lib.storage.StorageFile
import com.swordfish.lemuroid.lib.storage.scanner.SerialScanner
import timber.log.Timber
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object DocumentFileParser {
    private const val MAX_CHECKED_ENTRIES = 5
    private const val MAX_SIZE_CRC32 = 1_000_000_000
    private const val SINGLE_ROM_SIZE_RATIO = 0.9f

    private val ARCADE_SYSTEM_FOLDERS = setOf(
        SystemID.FBNEO.dbname.lowercase(),
        SystemID.MAME2003PLUS.dbname.lowercase(),
    )

    private val NON_ROM_EXTENSIONS = setOf(
        "ips", "ups", "bps", "xdelta", "cht", "txt", "nfo", "diz",
        "xml", "htm", "html", "png", "jpg", "jpeg", "bmp", "gif",
        "pdf", "sav", "srm", "state", "url", "zip", "rar", "7z", "tar", "gz"
    )

    private val KNOWN_ROM_EXTENSIONS = setOf(
        "gb", "gbc", "gba", "nes", "sfc", "smc", "gen", "smd", "md",
        "sms", "gg", "n64", "z64", "v64", "nds", "a26", "a78", "lnx",
        "pce", "ngp", "ngc", "ws", "wsc", "dosz", "3ds", "iso", "cso",
        "chd", "pbp"
    )

    fun parseDocumentFile(
        context: Context,
        baseStorageFile: BaseStorageFile,
    ): StorageFile {
        val isZip = baseStorageFile.extension.equals("zip", ignoreCase = true)
        return when {
            isZip && isArcadeArchive(baseStorageFile) -> parseArcadeFile(baseStorageFile)
            isZip -> {
                Timber.d("Detected zip file. ${baseStorageFile.name}")
                parseZipFile(context, baseStorageFile)
            }
            else -> {
                Timber.d("Detected standard file. ${baseStorageFile.name}")
                parseStandardFile(context, baseStorageFile)
            }
        }
    }

    private fun parseArcadeFile(baseStorageFile: BaseStorageFile): StorageFile {
        Timber.d("Detected arcade archive. ${baseStorageFile.name}")
        return StorageFile(
            name = baseStorageFile.name,
            size = baseStorageFile.size,
            crc = null,
            serial = null,
            uri = baseStorageFile.uri,
            path = baseStorageFile.uri.path,
            systemID = null,
        )
    }

    internal fun isArcadeArchive(baseStorageFile: BaseStorageFile): Boolean {
        return isArcadePath(baseStorageFile.path) || isArcadePath(baseStorageFile.uri.path)
    }

    internal fun isArcadePath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val segments = path.lowercase()
            .split('/', '\\', ':')
            .filter { it.isNotBlank() }
        if (segments.size <= 1) return false
        return segments.dropLast(1).any { it in ARCADE_SYSTEM_FOLDERS }
    }

    private fun parseZipFile(
        context: Context,
        baseStorageFile: BaseStorageFile,
    ): StorageFile {
        val inputStream = context.contentResolver.openInputStream(baseStorageFile.uri)
        return ZipInputStream(inputStream).use {
            val gameEntry = findGameEntry(it, baseStorageFile.size)
            if (gameEntry != null) {
                Timber.d("Handing zip file as compressed game: ${baseStorageFile.name}")
                parseCompressedGame(baseStorageFile, gameEntry, it)
            } else {
                Timber.d("Handing zip file as standard: ${baseStorageFile.name}")
                parseStandardFile(context, baseStorageFile)
            }
        }
    }

    private fun parseCompressedGame(
        baseStorageFile: BaseStorageFile,
        entry: ZipEntry,
        zipInputStream: ZipInputStream,
    ): StorageFile {
        Timber.d("Processing zipped entry: ${entry.name}")

        val diskInfo = SerialScanner.extractInfo(entry.name, zipInputStream)

        return StorageFile(
            entry.name,
            entry.size,
            entry.crc.toStringCRC32(),
            diskInfo.serial,
            baseStorageFile.uri,
            baseStorageFile.uri.path,
            diskInfo.systemID,
        )
    }

    private fun parseStandardFile(
        context: Context,
        baseStorageFile: BaseStorageFile,
    ): StorageFile {
        val diskInfo =
            context.contentResolver.openInputStream(baseStorageFile.uri)
                ?.let { inputStream -> SerialScanner.extractInfo(baseStorageFile.name, inputStream) }

        val crc32 =
            if (baseStorageFile.size < MAX_SIZE_CRC32 && diskInfo?.serial == null) {
                context.contentResolver.openInputStream(baseStorageFile.uri)?.calculateCrc32()
            } else {
                null
            }

        Timber.d("Parsed standard file: $baseStorageFile")

        return StorageFile(
            baseStorageFile.name,
            baseStorageFile.size,
            crc32,
            diskInfo?.serial,
            baseStorageFile.uri,
            baseStorageFile.uri.path,
            diskInfo?.systemID,
        )
    }

    fun findGameEntry(
        openedInputStream: ZipInputStream,
        fileSize: Long = -1,
    ): ZipEntry? {
        var checkedEntries = 0
        while (checkedEntries < MAX_CHECKED_ENTRIES) {
            val entry = openedInputStream.nextEntry ?: break
            if (isIgnoredEntry(entry)) continue
            checkedEntries++
            if (isGameEntry(entry, fileSize)) {
                return entry
            }
        }
        return null
    }

    private fun isIgnoredEntry(entry: ZipEntry): Boolean {
        if (entry.isDirectory) return true
        val name = entry.name
        return name.startsWith("__MACOSX/", ignoreCase = true) ||
            name.contains("/__MACOSX/", ignoreCase = true) ||
            name.substringAfterLast('/').startsWith("._")
    }

    private fun isGameEntry(
        entry: ZipEntry,
        fileSize: Long,
    ): Boolean {
        if (isIgnoredEntry(entry)) return false
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        if (ext in NON_ROM_EXTENSIONS) {
            return false
        }
        if (ext in KNOWN_ROM_EXTENSIONS) {
            return true
        }
        if (fileSize > 0 && entry.compressedSize > 0) {
            return (entry.compressedSize.toFloat() / fileSize.toFloat()) > SINGLE_ROM_SIZE_RATIO
        }
        return false
    }
}

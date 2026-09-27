package com.docdoc.app

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Native Android TTS plugin.
 *
 * - Reads text with Android TextToSpeech.
 * - Synthesizes WAV files offline when the installed TTS engine supports it.
 * - Merges PCM WAV chunks into one valid WAV.
 * - Saves the result to Music/DocDocTTS using MediaStore on Android 10+.
 *
 * The web UI may use Web Speech API for playback, while this plugin is used
 * for native playback fallback and file export.
 */
@CapacitorPlugin(name = "TtsFileSaver")
class TtsFileSaverPlugin : Plugin() {

    companion object {
        private const val DEFAULT_LANG = "vi-VN"
        private const val SAFE_CHUNK_LEN = 350
        private const val READY_TIMEOUT_SECONDS = 8L
        private const val SYNTH_CHUNK_TIMEOUT_SECONDS = 30L
        private const val SPEAK_CHUNK_TIMEOUT_SECONDS = 120L
    }

    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsReady = false

    private val ttsReadyLatch = CountDownLatch(1)
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var speakGeneration = 0L

    override fun load() {
        super.load()

        // Capacitor loads the plugin on the main thread; TextToSpeech initialization
        // should therefore happen here rather than on the worker executor.
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            ttsReadyLatch.countDown()
        }
    }

    private fun awaitTtsReady(): Boolean {
        if (ttsReady && tts != null) return true
        return try {
            ttsReadyLatch.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS) &&
                ttsReady && tts != null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    @PluginMethod
    fun getVoices(call: PluginCall) {
        executor.execute {
            if (!awaitTtsReady()) {
                call.reject("Bộ máy Text-to-Speech chưa sẵn sàng. Vui lòng thử lại sau vài giây.")
                return@execute
            }

            try {
                val arr = JSArray()
                val voices = tts?.voices.orEmpty()
                    .sortedWith(compareBy({ it.locale.language }, { it.name }))

                voices.forEach { voice ->
                    val obj = JSObject()
                    obj.put("name", voice.name)
                    obj.put("lang", voice.locale.toLanguageTag())
                    arr.put(obj)
                }

                val ret = JSObject()
                ret.put("voices", arr)
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Không thể lấy danh sách giọng đọc: ${e.message}", e)
            }
        }
    }

    @PluginMethod
    fun speak(call: PluginCall) {
        val text = call.getString("text")
        if (text.isNullOrBlank()) {
            call.reject("Thiếu văn bản cần đọc (text).")
            return
        }

        val voiceName = call.getString("voiceName").orEmpty()
        val lang = call.getString("lang").orEmpty().ifBlank { DEFAULT_LANG }
        val rate = sanitizeRate(call.getFloat("rate") ?: 1.0f)
        val pitch = sanitizePitch(call.getFloat("pitch") ?: 1.0f)

        // A new speak request invalidates an older one.
        val generation = synchronized(this) {
            speakGeneration += 1
            speakGeneration
        }

        executor.execute {
            if (!awaitTtsReady()) {
                call.reject("Bộ máy Text-to-Speech chưa sẵn sàng. Vui lòng thử lại sau vài giây.")
                return@execute
            }

            try {
                val engine = tts ?: throw IOException("TTS engine không khả dụng.")
                applyVoiceSettings(engine, voiceName, lang, rate, pitch)

                val chunks = splitIntoChunks(text, SAFE_CHUNK_LEN)
                if (chunks.isEmpty()) {
                    call.reject("Văn bản rỗng sau khi xử lý.")
                    return@execute
                }

                for ((index, chunk) in chunks.withIndex()) {
                    if (generation != speakGeneration) return@execute

                    val utteranceId = "speak_${generation}_$index"
                    val latch = CountDownLatch(1)
                    var errorMessage: String? = null

                    val listener = object : UtteranceProgressListener() {
                        override fun onStart(id: String?) = Unit

                        override fun onDone(id: String?) {
                            if (id == utteranceId) latch.countDown()
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            if (id == utteranceId) {
                                errorMessage = "TTS engine báo lỗi."
                                latch.countDown()
                            }
                        }

                        override fun onError(id: String?, errorCode: Int) {
                            if (id == utteranceId) {
                                errorMessage = "TTS engine báo lỗi (mã $errorCode)."
                                latch.countDown()
                            }
                        }

                        override fun onStop(id: String?, interrupted: Boolean) {
                            if (id == utteranceId) latch.countDown()
                        }
                    }

                    engine.setOnUtteranceProgressListener(listener)

                    val result = engine.speak(
                        chunk,
                        if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                        Bundle(),
                        utteranceId
                    )

                    if (result != TextToSpeech.SUCCESS) {
                        throw IOException("Không thể bắt đầu đọc đoạn ${index + 1}/${chunks.size}.")
                    }

                    val finished = latch.await(SPEAK_CHUNK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    if (!finished) {
                        engine.stop()
                        throw IOException("TTS không phản hồi sau ${SPEAK_CHUNK_TIMEOUT_SECONDS} giây.")
                    }

                    if (generation != speakGeneration) {
                        // stopSpeaking() invalidated this session. Resolve the
                        // original JS promise so the WebView never remains stuck
                        // waiting for a native speak() call that was intentionally stopped.
                        call.resolve()
                        return@execute
                    }
                    if (errorMessage != null) throw IOException(errorMessage)
                }

                if (generation == speakGeneration) call.resolve()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                call.reject("Đã dừng quá trình đọc.")
            } catch (e: Exception) {
                if (generation == speakGeneration) {
                    call.reject("Lỗi khi đọc văn bản: ${e.message}", e)
                }
            }
        }
    }

    @PluginMethod
    fun stopSpeaking(call: PluginCall) {
        synchronized(this) {
            speakGeneration += 1
        }
        // Must be called immediately rather than queued on executor; the executor
        // may be waiting on a TTS callback.
        try {
            tts?.stop()
        } catch (_: Exception) {
            // Stopping is best-effort.
        }
        call.resolve()
    }

    @PluginMethod
    fun shareFile(call: PluginCall) {
        try {
            val uriString = call.getString("uri")?.trim().orEmpty()
            if (uriString.isBlank()) {
                call.reject("Thiếu URI của file cần chia sẻ.")
                return
            }

            val uri = Uri.parse(uriString)
            val fileName = call.getString("fileName")?.trim().orEmpty().ifBlank { "audio.wav" }
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_TITLE, fileName)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri(fileName, uri)
            }

            val chooser = android.content.Intent.createChooser(shareIntent, "Chia sẻ file âm thanh")
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            call.resolve()
        } catch (e: Exception) {
            call.reject("Không thể mở chức năng chia sẻ file: ${e.message}", e)
        }
    }

    @PluginMethod
    fun synthesizeToFile(call: PluginCall) {
        val text = call.getString("text")
        if (text.isNullOrBlank()) {
            call.reject("Thiếu văn bản cần đọc (text).")
            return
        }

        val voiceName = call.getString("voiceName").orEmpty()
        val lang = call.getString("lang").orEmpty().ifBlank { DEFAULT_LANG }
        val rate = sanitizeRate(call.getFloat("rate") ?: 1.0f)
        val pitch = sanitizePitch(call.getFloat("pitch") ?: 1.0f)

        executor.execute {
            var tmpDir: File? = null
            var mergedFile: File? = null
            var savedUri: Uri? = null

            try {
                if (!awaitTtsReady()) {
                    call.reject("Bộ máy Text-to-Speech chưa sẵn sàng. Vui lòng thử lại sau vài giây.")
                    return@execute
                }

                val engine = tts ?: throw IOException("TTS engine không khả dụng.")
                applyVoiceSettings(engine, voiceName, lang, rate, pitch)

                val chunks = splitIntoChunks(text, SAFE_CHUNK_LEN)
                if (chunks.isEmpty()) {
                    call.reject("Văn bản rỗng sau khi xử lý.")
                    return@execute
                }

                tmpDir = File(context.cacheDir, "tts_tmp_${UUID.randomUUID()}").also {
                    if (!it.mkdirs() && !it.isDirectory) {
                        throw IOException("Không thể tạo thư mục tạm.")
                    }
                }

                val chunkFiles = ArrayList<File>(chunks.size)

                for ((index, chunkText) in chunks.withIndex()) {
                    val chunkFile = File(tmpDir, "chunk_%04d.wav".format(index))
                    synthesizeChunk(engine, chunkText, chunkFile, index, chunks.size)
                    validateWav(chunkFile)
                    chunkFiles.add(chunkFile)
                }

                mergedFile = File(context.cacheDir, "tts_output_${UUID.randomUUID()}.wav")
                mergeWavFiles(chunkFiles, mergedFile)

                val fileName = "doc-van-ban-${System.currentTimeMillis()}.wav"
                savedUri = saveWavToPublicStorage(mergedFile, fileName)
                if (savedUri == null) {
                    throw IOException("Không thể lưu file âm thanh vào bộ nhớ thiết bị.")
                }

                val ret = JSObject()
                ret.put("uri", savedUri.toString())
                ret.put("fileName", fileName)
                call.resolve(ret)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                call.reject("Đã dừng quá trình tạo file.")
            } catch (e: Exception) {
                // If MediaStore insertion succeeded but copying/updating failed,
                // remove the incomplete item so it does not remain as a ghost file.
                savedUri?.let { uri ->
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                }
                call.reject("Lỗi khi tạo file âm thanh: ${e.message}", e)
            } finally {
                try {
                    tmpDir?.deleteRecursively()
                    mergedFile?.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun synthesizeChunk(
        engine: TextToSpeech,
        text: String,
        outputFile: File,
        index: Int,
        total: Int
    ) {
        val utteranceId = "file_${UUID.randomUUID()}"
        val latch = CountDownLatch(1)
        var errorMessage: String? = null

        val listener = object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit

            override fun onDone(id: String?) {
                if (id == utteranceId) latch.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) {
                    errorMessage = "TTS engine báo lỗi."
                    latch.countDown()
                }
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId) {
                    errorMessage = "TTS engine báo lỗi (mã $errorCode)."
                    latch.countDown()
                }
            }

            override fun onStop(id: String?, interrupted: Boolean) {
                if (id == utteranceId) latch.countDown()
            }
        }

        engine.setOnUtteranceProgressListener(listener)

        // File overload is the Android API used by TextToSpeech to write WAV data.
        val result = engine.synthesizeToFile(text, Bundle(), outputFile, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            throw IOException("Không thể bắt đầu tổng hợp đoạn $index/$total.")
        }

        val finished = latch.await(SYNTH_CHUNK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            engine.stop()
            throw IOException("Không thể tổng hợp đoạn $index/$total trong thời gian cho phép.")
        }

        if (errorMessage != null) throw IOException(errorMessage)
    }

    private fun applyVoiceSettings(
        engine: TextToSpeech,
        voiceName: String,
        lang: String,
        rate: Float,
        pitch: Float
    ) {
        var chosenVoice: Voice? = null

        if (voiceName.isNotBlank()) {
            chosenVoice = engine.voices?.firstOrNull { it.name == voiceName }
        }

        val locale = parseLocale(lang)
        if (chosenVoice == null) {
            chosenVoice = engine.voices?.firstOrNull {
                it.locale.toLanguageTag().equals(locale.toLanguageTag(), ignoreCase = true)
            } ?: engine.voices?.firstOrNull {
                it.locale.language.equals(locale.language, ignoreCase = true)
            }
        }

        if (chosenVoice != null) {
            engine.voice = chosenVoice
        } else {
            val result = engine.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                throw IOException("Thiết bị chưa cài giọng cho ngôn ngữ ${locale.toLanguageTag()}.")
            }
        }

        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
    }

    private fun sanitizeRate(value: Float): Float =
        if (value.isFinite() && value in 0.1f..4.0f) value else 1.0f

    private fun sanitizePitch(value: Float): Float =
        if (value.isFinite() && value in 0.1f..2.0f) value else 1.0f

    private fun parseLocale(bcp47: String): Locale {
        val locale = Locale.forLanguageTag(bcp47)
        return if (locale.language.isNullOrBlank()) Locale.forLanguageTag(DEFAULT_LANG) else locale
    }

    private fun splitIntoChunks(text: String, maxLen: Int): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return emptyList()

        val rawSentences = normalized
            .split(Regex("(?<=[.!?…])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val chunks = ArrayList<String>()
        var buffer = ""

        fun flushBuffer() {
            if (buffer.isNotEmpty()) {
                chunks.add(buffer)
                buffer = ""
            }
        }

        for (sentence in rawSentences) {
            if (sentence.length > maxLen) {
                flushBuffer()
                var remaining = sentence
                while (remaining.length > maxLen) {
                    var cut = remaining.lastIndexOf(' ', maxLen)
                    if (cut <= 0) cut = maxLen
                    chunks.add(remaining.substring(0, cut).trim())
                    remaining = remaining.substring(cut).trim()
                }
                if (remaining.isNotEmpty()) buffer = remaining
                continue
            }

            val candidateLength = if (buffer.isEmpty()) {
                sentence.length
            } else {
                buffer.length + 1 + sentence.length
            }

            if (candidateLength > maxLen) {
                flushBuffer()
                buffer = sentence
            } else {
                buffer = if (buffer.isEmpty()) sentence else "$buffer $sentence"
            }
        }

        flushBuffer()
        return chunks
    }

    private data class WavInfo(
        val dataOffset: Long,
        val dataSize: Long,
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val byteRate: Int,
        val blockAlign: Int,
        val bitsPerSample: Int
    )

    private fun readLe16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readLe32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun readAscii(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

    private fun readWavInfo(file: File): WavInfo {
        FileInputStream(file).use { input ->
            val header = ByteArray(12)
            readFully(input, header)
            if (readAscii(header, 0, 4) != "RIFF" || readAscii(header, 8, 4) != "WAVE") {
                throw IOException("File WAV không hợp lệ: ${file.name}")
            }

            var fmt: WavInfo? = null
            var dataOffset = -1L
            var dataSize = -1L
            var position = 12L

            val chunkHeader = ByteArray(8)
            while (position + 8 <= file.length()) {
                readFully(input, chunkHeader)
                val chunkId = readAscii(chunkHeader, 0, 4)
                val chunkSize = readLe32(chunkHeader, 4)
                val payloadOffset = position + 8

                if (chunkSize < 0 || payloadOffset + chunkSize > file.length()) {
                    throw IOException("Chunk WAV không hợp lệ: ${file.name}")
                }

                when (chunkId) {
                    "fmt " -> {
                        // PCM fmt chunks are small; reading the entire chunk prevents
                        // the stream cursor from becoming desynchronized when an
                        // extended fmt chunk is encountered.
                        if (chunkSize < 16L || chunkSize > 1024L) {
                            throw IOException("fmt chunk không hợp lệ.")
                        }
                        val fmtBytes = ByteArray(chunkSize.toInt())
                        readFully(input, fmtBytes)
                        val audioFormat = readLe16(fmtBytes, 0)
                        val channels = readLe16(fmtBytes, 2)
                        val sampleRate = readLe32(fmtBytes, 4).toInt()
                        val byteRate = readLe32(fmtBytes, 8).toInt()
                        val blockAlign = readLe16(fmtBytes, 12)
                        val bitsPerSample = readLe16(fmtBytes, 14)
                        fmt = WavInfo(0, 0, audioFormat, channels, sampleRate, byteRate, blockAlign, bitsPerSample)
                    }

                    "data" -> {
                        dataOffset = payloadOffset
                        dataSize = chunkSize
                        input.skipFully(chunkSize)
                    }

                    else -> input.skipFully(chunkSize)
                }

                // RIFF chunks are word aligned.
                val padding = if ((chunkSize and 1L) != 0L) 1L else 0L
                if (padding != 0L) input.skipFully(1)
                position = payloadOffset + chunkSize + padding
            }

            val format = fmt ?: throw IOException("WAV không có fmt chunk.")
            if (dataOffset < 0 || dataSize < 0) throw IOException("WAV không có data chunk.")

            return format.copy(dataOffset = dataOffset, dataSize = dataSize)
        }
    }

    private fun validateWav(file: File): WavInfo {
        if (!file.exists() || file.length() <= 44L) {
            throw IOException("TTS không tạo được file WAV hợp lệ.")
        }
        val info = readWavInfo(file)
        if (info.audioFormat != 1) {
            throw IOException("TTS tạo định dạng WAV không phải PCM.")
        }
        if (info.channels <= 0 ||
            info.sampleRate <= 0 ||
            info.bitsPerSample <= 0 ||
            info.blockAlign <= 0 ||
            info.byteRate <= 0 ||
            info.dataSize <= 0 ||
            info.dataSize % info.blockAlign.toLong() != 0L
        ) {
            throw IOException("Thông số WAV không hợp lệ.")
        }
        return info
    }

    private fun mergeWavFiles(files: List<File>, outFile: File) {
        if (files.isEmpty()) throw IOException("Không có đoạn âm thanh nào để ghép.")

        val infos = files.map { validateWav(it) }
        val first = infos.first()

        // All chunks must have the same PCM format; otherwise concatenating their
        // raw sample bytes would produce corrupted audio.
        infos.forEachIndexed { index, info ->
            if (info.audioFormat != first.audioFormat ||
                info.channels != first.channels ||
                info.sampleRate != first.sampleRate ||
                info.byteRate != first.byteRate ||
                info.blockAlign != first.blockAlign ||
                info.bitsPerSample != first.bitsPerSample
            ) {
                throw IOException("Định dạng WAV giữa các đoạn không đồng nhất (đoạn ${index + 1}).")
            }
        }

        val totalDataSize = infos.sumOf { it.dataSize }
        if (totalDataSize > 0xFFFFFFFFL - 36L) {
            throw IOException("File WAV vượt quá giới hạn kích thước.")
        }

        BufferedOutputStream(FileOutputStream(outFile)).use { output ->
            writeWavHeader(output, first, totalDataSize)

            val buffer = ByteArray(8192)
            files.forEachIndexed { index, file ->
                FileInputStream(file).use { input ->
                    input.skipFully(infos[index].dataOffset)
                    var remaining = infos[index].dataSize
                    while (remaining > 0) {
                        val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = input.read(buffer, 0, wanted)
                        if (read <= 0) throw IOException("Không thể đọc dữ liệu WAV.")
                        output.write(buffer, 0, read)
                        remaining -= read.toLong()
                    }
                }
            }
        }
    }

    private fun writeWavHeader(
        out: OutputStream,
        info: WavInfo,
        dataSize: Long
    ) {
        val header = ByteArray(44)
        writeAscii(header, 0, "RIFF")
        writeLe32(header, 4, dataSize + 36)
        writeAscii(header, 8, "WAVE")
        writeAscii(header, 12, "fmt ")
        writeLe32(header, 16, 16)
        writeLe16(header, 20, info.audioFormat)
        writeLe16(header, 22, info.channels)
        writeLe32(header, 24, info.sampleRate.toLong())
        writeLe32(header, 28, info.byteRate.toLong())
        writeLe16(header, 32, info.blockAlign)
        writeLe16(header, 34, info.bitsPerSample)
        writeAscii(header, 36, "data")
        writeLe32(header, 40, dataSize)
        out.write(header)
    }

    private fun writeLe16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeLe32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeAscii(bytes: ByteArray, offset: Int, value: String) {
        val encoded = value.toByteArray(Charsets.US_ASCII)
        System.arraycopy(encoded, 0, bytes, offset, encoded.size)
    }

    private fun readFully(input: FileInputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw IOException("File WAV bị thiếu dữ liệu.")
            offset += read
        }
    }

    private fun FileInputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (read() < 0) throw IOException("Không thể bỏ qua dữ liệu WAV.")
                remaining--
            }
        }
    }

    /**
     * Android 10+ only: MediaStore gives a shareable content:// URI and avoids
     * legacy storage permissions. This app's supported deployment target is
     * therefore Android 10 (API 29) or newer.
     */
    private fun saveWavToPublicStorage(sourceFile: File, displayName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("Phiên bản ứng dụng này yêu cầu Android 10 (API 29) trở lên để lưu WAV.")
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/DocDocTTS")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val itemUri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore không tạo được mục file.")

        try {
            resolver.openOutputStream(itemUri, "w")?.use { output ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Không thể mở file đích để ghi.")

            val completed = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            resolver.update(itemUri, completed, null, null)
            return itemUri
        } catch (e: Exception) {
            try {
                resolver.delete(itemUri, null, null)
            } catch (_: Exception) {
            }
            throw e
        }
    }

    override fun handleOnDestroy() {
        synchronized(this) {
            speakGeneration += 1
        }
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        tts?.shutdown()
        tts = null
        executor.shutdownNow()
        super.handleOnDestroy()
    }
}

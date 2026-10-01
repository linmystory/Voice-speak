package com.docdoc.app

// ============================================================================
// TtsFileSaverPlugin.kt
//
// Plugin Capacitor native cho Android, dùng để tổng hợp văn bản thành GIỌNG NÓI
// và LƯU RA FILE WAV THẬT trên thiết bị, sử dụng bộ máy TextToSpeech (TTS) có
// sẵn của hệ điều hành Android — hoàn toàn offline, không cần API key hay
// dịch vụ đám mây nào.
//
// PHIÊN BẢN ĐÃ VÁ LỖI (bản "hoàn thiện"):
//   - Báo trạng thái engine rõ ràng (đang tải / sẵn sàng / lỗi) qua
//     getEngineStatus(), thay vì để JS chờ vô thời hạn với thông báo mơ hồ.
//   - Toàn bộ luồng nền được bọc try/catch(Throwable) để một lỗi bất ngờ
//     (kể cả OutOfMemoryError khi ghép file quá dài) không làm crash cả ứng
//     dụng, mà chỉ trả lỗi có kiểm soát về cho JS.
//   - Kiểm tra kết quả setLanguage()/setVoice() để tự động rơi về giọng mặc
//     định khi ngôn ngữ yêu cầu không có sẵn trên máy, thay vì đọc sai giọng
//     mà không báo gì.
//
// ĐÃ THU GỌN: bản này CHỈ HỖ TRỢ ANDROID 10 (API 29) TRỞ LÊN.
// Từ Android 10, việc ghi file vào bộ nhớ công khai dùng MediaStore + scoped
// storage nên:
//   - KHÔNG cần xin quyền WRITE_EXTERNAL_STORAGE lúc chạy.
//   - KHÔNG cần khai báo <provider> FileProvider trong AndroidManifest.xml.
//   - KHÔNG cần script vá manifest (scripts/patch_manifest.py) hay file
//     res/xml/file_paths.xml nữa — cả hai đã được loại khỏi project.
// minSdkVersion của project (android/variables.gradle) phải đặt là 29 để
// Play Store / trình cài đặt tự chặn máy Android cũ hơn.
//
// Vì sao cần plugin native riêng?
// Web Speech API (window.speechSynthesis) chạy trong WebView CHỈ có thể phát
// âm thanh trực tiếp ra loa, không cho phép lấy dữ liệu âm thanh dưới dạng
// file. Ngược lại, Android cung cấp hàm gốc:
//     TextToSpeech.synthesizeToFile(text, params, file, utteranceId)
// hàm này ghi thẳng dữ liệu âm thanh (PCM/WAV) ra file mà KHÔNG phát ra loa.
// Plugin này gọi hàm đó, xử lý việc chia nhỏ văn bản dài (do TTS có giới hạn
// độ dài mỗi lần tổng hợp), rồi ghép nhiều đoạn WAV lại thành một file hoàn
// chỉnh duy nhất.
//
// Cách cài đặt: xem HUONG_DAN_CAI_DAT.md đi kèm trong dự án.
// ============================================================================

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@CapacitorPlugin(name = "TtsFileSaver")
class TtsFileSaverPlugin : Plugin() {

    companion object {
        private const val TAG = "TtsFileSaverPlugin"

        // Trạng thái engine TTS, JS dùng để hiển thị đúng thông báo thay vì
        // đoán mò khi mọi lệnh đều bị reject với cùng 1 câu chung chung.
        private const val STATUS_LOADING = "loading"
        private const val STATUS_READY = "ready"
        private const val STATUS_ERROR = "error"
    }

    private var tts: TextToSpeech? = null

    // AudioFocusRequest đang giữ (nếu có) cho phiên đọc hiện tại. Giữ nguyên
    // trong suốt phiên đọc (nhiều câu nối tiếp), CHỈ xin 1 LẦN lúc bắt đầu
    // (warmup=true) và nhả ra khi dừng hẳn — xin/nhả liên tục giữa từng câu
    // mới chính là nguyên nhân gây tiếng "khựng/ngắt" giữa các câu.
    @Volatile private var audioFocusRequest: AudioFocusRequest? = null

    // WakeLock giữ CPU thức trong lúc đang đọc dài. Nếu người dùng khóa màn
    // hình/chuyển app khác giữa lúc đọc, Android có thể đưa CPU vào chế độ
    // Doze/ngủ đông, làm luồng đọc (executor thread đang chờ latch) bị treo
    // giữa chừng -> đúng triệu chứng "âm thanh ngắt giữa chừng" khi khóa máy.
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    // Package name của engine TTS đang active (dùng cho speak()/synthesizeToFile()).
    // null nghĩa là đang dùng engine mặc định của hệ thống.
    @Volatile private var activeEngineName: String? = null

    @Volatile private var engineStatus: String = STATUS_LOADING

    @Volatile private var engineErrorMessage: String = ""

    // executor riêng cho toàn bộ thao tác TTS (đồng bộ hoá truy cập engine),
    // isShutdown/isTerminated được kiểm tra trước khi execute() để tránh
    // RejectedExecutionException sau khi Activity đã bị huỷ.
    private var executor: ExecutorService = Executors.newSingleThreadExecutor()

    // Độ dài an toàn mỗi đoạn văn bản gửi cho TTS (ký tự). Android TTS có giới
    // hạn thực tế do TextToSpeech.getMaxSpeechInputLength() cung cấp, nhưng ta
    // dùng một ngưỡng an toàn nhỏ hơn để tránh lỗi trên các máy/engine khác nhau.
    private val SAFE_CHUNK_LEN = 350

    // ------------------------------------------------------------------
    // XUNG ĐỘT VỚI TALKBACK / ỨNG DỤNG KHÁC DÙNG CHUNG BỘ ĐỌC (TTS ENGINE)
    //
    // Khi ta và TalkBack (trình đọc màn hình của Google) cùng dùng MỘT engine,
    // TalkBack phát giọng bằng chế độ QUEUE_DESTROY: chế độ này xoá sạch hàng
    // đợi của TẤT CẢ ứng dụng đang dùng chung engine để TalkBack nói trước
    // (AOSP: "Talkback uses it to preempt other users of TextToSpeech queue").
    // Framework báo việc này cho ta qua UtteranceProgressListener.onStop() —
    // KHÔNG phải onDone/onError. Bản cũ không xử lý onStop nên cứ đứng chờ hết
    // 30 giây (đọc) hoặc 20 giây (lưu file, rồi báo lỗi) mới đi tiếp. Khi dùng
    // bộ đọc RIÊNG (khác bộ đọc của TalkBack) thì TalkBack không chạm tới
    // hàng đợi của ta nên mọi thứ bình thường — đúng với hiện tượng người dùng
    // mô tả. Cách xử lý: coi onStop không do mình gây ra là "bị chen ngang" và
    // tự xếp lại đúng đoạn đó vào hàng đợi (sau lời của TalkBack).
    // ------------------------------------------------------------------
    private val OUTCOME_PENDING = 0
    private val OUTCOME_DONE = 1
    private val OUTCOME_ERROR = 2
    private val OUTCOME_STOPPED = 3

    // Số lần thử lại tối đa cho 1 đoạn khi engine báo LỖI thật.
    private val MAX_ERROR_RETRIES = 2

    // Số lần thử lại tối đa cho 1 đoạn khi bị bộ đọc khác chen ngang. Đặt cao vì
    // người dùng TalkBack có thể vuốt liên tục; mỗi lần chờ tăng dần tới 1,5 giây.
    private val MAX_PREEMPT_RETRIES = 40

    // Mỗi lần gửi đoạn cho engine dùng 1 utteranceId DUY NHẤT (không trùng giữa
    // các phiên đọc/lần thử): tránh việc callback trễ của phiên cũ bị nhầm với
    // đoạn mới có cùng số thứ tự.
    private val utteranceSeq = AtomicLong(0)
    private fun newUtteranceId(prefix: String): String = "${prefix}_${utteranceSeq.incrementAndGet()}"

    // Đọc tham số số thực (rate/pitch) từ JS một cách chịu lỗi: chấp nhận Int/Long/
    // Float/Double (JSON của JS gửi 1.0 thành 1, 2.0 thành 2 -> org.json trả Integer)
    // và cả chuỗi số; NaN/Infinity/thiếu -> dùng giá trị mặc định. Không phụ thuộc
    // vào việc PluginCall.getFloat() có xử lý đủ các kiểu số hay không, để tốc độ
    // "1.0x"/"2.0x" không bao giờ bị âm thầm rơi về mặc định.
    private fun readFloatParam(call: PluginCall, name: String, default: Float): Float {
        val raw: Any? = try { call.data.opt(name) } catch (_: Throwable) { null }
        val parsed: Float? = when (raw) {
            is Number -> raw.toFloat()
            is String -> raw.trim().toFloatOrNull()
            else -> null
        }
        return if (parsed == null || parsed.isNaN() || parsed.isInfinite()) default else parsed
    }

    // Chờ (có thể bị ngắt) — trả về false nếu người dùng đã bấm Dừng trong lúc chờ.
    private fun sleepUnlessCancelled(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (speakCancelled) return false
            try {
                Thread.sleep(minOf(50L, end - System.currentTimeMillis()).coerceAtLeast(1L))
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !speakCancelled
    }

    // Đoạn dùng khi XUẤT FILE: lớn hơn đoạn đọc trực tiếp vì tổng hợp ra file
    // không cần phản hồi tức thì; ít đoạn hơn = ít lần gọi engine hơn = nhanh hơn
    // (mỗi lần gọi engine có chi phí cố định vài chục tới vài trăm ms). Vẫn thấp
    // hơn nhiều so với giới hạn TextToSpeech.getMaxSpeechInputLength() (4000).
    private val FILE_CHUNK_LEN = 800

    // Số đoạn được xếp hàng trước cho engine khi xuất file. Engine luôn có sẵn
    // việc để làm ngay khi vừa xong đoạn trước (không còn khoảng trống chờ ta
    // nhận callback rồi mới gửi đoạn kế như bản cũ).
    private val FILE_PIPELINE_DEPTH = 3

    // Thời gian chờ tối đa (giây) cho MỖI kết quả của engine khi xuất file. Engine
    // xử lý tuần tự nên đây chính là thời gian tối đa cho 1 đoạn (~800 ký tự);
    // chỉ dùng để phát hiện engine "im lặng" hẳn, không phải giới hạn tốc độ.
    private val FILE_EVENT_TIMEOUT_SEC = 90L

    // Đọc nối liền: xếp trước đoạn kế tiếp vào hàng đợi của engine trong lúc đoạn hiện
    // tại đang phát (xem vòng đọc trong speak()). false = quay về cách cũ.
    private val SPEAK_PREFETCH_NEXT = true

    // WakeLock tự hết hạn sau WAKE_LOCK_TIMEOUT_MS; vòng đọc gia hạn mỗi WAKE_LOCK_REFRESH_MS
    // để bài đọc dài hơn 10 phút (khóa màn hình) không bị Doze làm treo giữa chừng.
    private val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
    private val WAKE_LOCK_REFRESH_MS = 60_000L

    // Một đoạn nhỏ của 1 câu cần đọc (danh sách phẳng, theo đúng thứ tự đọc).
    private class SpeakItem(val sentenceIndex: Int, val subIdx: Int, val text: String)

    // Một đoạn đã gửi cho engine và đang chờ kết quả (xong / lỗi / bị dừng).
    private class SpeakSlot(val id: String, val latch: CountDownLatch, val outcome: AtomicInteger)

    override fun load() {
        super.load()
        try {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    // Ghi lại đúng package name của engine mặc định hệ thống đang
                    // dùng, để lần gọi getEnginesWithVoices() đầu tiên biết chính
                    // xác có cần chuyển engine hay không (tránh 1 lần switch thừa
                    // không cần thiết ngay khi mở app).
                    activeEngineName = tts?.defaultEngine
                    tts?.let { configureEngineAudioAttributes(it) }
                    engineStatus = STATUS_READY
                } else {
                    engineStatus = STATUS_ERROR
                    engineErrorMessage =
                        "Không khởi tạo được bộ máy Text-to-Speech (mã lỗi $status). " +
                        "Máy có thể chưa cài ứng dụng chuyển văn bản thành giọng nói nào."
                    Log.e(TAG, engineErrorMessage)
                }
            }
        } catch (t: Throwable) {
            // Một số thiết bị OEM (ROM tuỳ biến, không có Google TTS/Samsung TTS)
            // có thể ném lỗi ngay khi khởi tạo TextToSpeech thay vì trả status lỗi
            // qua callback. Bắt lại ở đây để tránh crash cả ứng dụng lúc mở app.
            engineStatus = STATUS_ERROR
            engineErrorMessage = "Không thể khởi tạo Text-to-Speech: ${t.message}"
            Log.e(TAG, engineErrorMessage, t)
        }
    }

    // ------------------------------------------------------------------
    // Cho JS hỏi trạng thái engine hiện tại, để hiển thị đúng thông báo
    // (đang tải / sẵn sàng / lỗi kèm lý do) thay vì đoán.
    // ------------------------------------------------------------------
    @PluginMethod
    fun getEngineStatus(call: PluginCall) {
        val ret = JSObject()
        ret.put("status", engineStatus)
        ret.put("message", engineErrorMessage)
        call.resolve(ret)
    }

    private fun ensureReadyOrReject(call: PluginCall): Boolean {
        return when (engineStatus) {
            STATUS_READY -> true
            STATUS_ERROR -> {
                call.reject(
                    engineErrorMessage.ifBlank {
                        "Bộ máy Text-to-Speech gặp lỗi và không sẵn sàng."
                    }
                )
                false
            }
            else -> {
                call.reject("Bộ máy Text-to-Speech đang khởi động, vui lòng thử lại sau vài giây.")
                false
            }
        }
    }

    private fun runOnExecutor(call: PluginCall, task: () -> Unit) {
        if (executor.isShutdown || executor.isTerminated) {
            call.reject("Ứng dụng đang đóng, không thể xử lý yêu cầu này.")
            return
        }
        try {
            executor.execute {
                try {
                    task()
                } catch (t: Throwable) {
                    // Lưới an toàn cuối cùng: bất kỳ lỗi không lường trước nào
                    // (kể cả OutOfMemoryError) đều được bắt lại ở đây để KHÔNG
                    // làm sập tiến trình ứng dụng, chỉ báo lỗi có kiểm soát.
                    Log.e(TAG, "Lỗi không mong muốn trong tác vụ TTS: ${t.message}", t)
                    try {
                        call.reject("Đã xảy ra lỗi không mong muốn: ${t.message}")
                    } catch (_: Throwable) {
                        // call có thể đã được resolve/reject trước đó, bỏ qua.
                    }
                }
            }
        } catch (t: Throwable) {
            call.reject("Không thể lên lịch tác vụ TTS: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // Hàm chính: nhận văn bản, tùy chọn giọng/tốc độ/cao độ, trả về Uri file
    // ------------------------------------------------------------------
    @PluginMethod
    fun synthesizeToFile(call: PluginCall) {
        val text = call.getString("text")
        if (text.isNullOrBlank()) {
            call.reject("Thiếu văn bản cần đọc (text).")
            return
        }
        if (!ensureReadyOrReject(call)) return

        // Nếu đang đọc dở qua loa: DỪNG NGAY để việc lưu file không phải xếp hàng
        // chờ đọc xong. Mọi thao tác engine dùng chung 1 luồng xử lý duy nhất và
        // engine chỉ làm được 1 việc mỗi lúc, nên nếu không dừng đọc trước thì việc
        // lưu file có thể phải chờ hết cả bài đọc (hàng chục phút với văn bản dài)
        // mới bắt đầu — đây là nguyên nhân khiến "lưu âm thanh quá lâu".
        stopEpoch.incrementAndGet()
        speakCancelled = true
        try { tts?.stop() } catch (_: Throwable) {}
        currentLatch?.countDown()
        abandonSpeechAudioFocus()

        // Android 10+ dùng MediaStore/scoped storage => không cần xin quyền
        // lưu trữ lúc chạy, tổng hợp file ngay.
        doSynthesizeToFile(call)
    }

    private fun doSynthesizeToFile(call: PluginCall) {
        val text = call.getString("text")
        if (text.isNullOrBlank()) {
            call.reject("Thiếu văn bản cần đọc (text).")
            return
        }

        val voiceName = call.getString("voiceName") ?: ""
        val lang = call.getString("lang") ?: "vi-VN"
        val rate = readFloatParam(call, "rate", 1.0f)
        val pitch = readFloatParam(call, "pitch", 1.0f)

        runOnExecutor(call) {
            var tmpDir: File? = null
            // Xuất file văn bản dài có thể mất nhiều giây đến vài phút — giữ CPU
            // thức để tránh Doze/App Standby làm treo giữa chừng nếu người dùng
            // khóa màn hình trong lúc chờ xuất file (cùng nguyên nhân với lúc
            // đọc qua loa, xem giải thích chi tiết ở acquireWakeLock()).
            acquireWakeLock()
            try {
                val engine = tts
                if (engine == null) {
                    call.reject("Bộ máy Text-to-Speech chưa sẵn sàng. Vui lòng thử lại sau vài giây.")
                    return@runOnExecutor
                }

                applyVoiceSettings(engine, voiceName, lang, rate, pitch)
                // Xuất file dùng đoạn LỚN hơn đoạn đọc trực tiếp (xem FILE_CHUNK_LEN).
                // Bỏ đoạn không có chữ/số nào (cùng lý do như khi đọc qua loa: engine không
                // gọi callback cho đoạn chỉ gồm dấu câu -> phải chờ hết 90 giây rồi báo lỗi).
                val chunks = splitIntoChunks(text, FILE_CHUNK_LEN).filter { c -> c.any { it.isLetterOrDigit() } }
                if (chunks.isEmpty()) {
                    call.reject("Văn bản rỗng sau khi xử lý.")
                    return@runOnExecutor
                }
                val total = chunks.size

                val workDir = File(context.cacheDir, "tts_tmp_${System.currentTimeMillis()}")
                if (!workDir.mkdirs() && !workDir.exists()) {
                    call.reject("Không tạo được thư mục tạm để xử lý âm thanh.")
                    return@runOnExecutor
                }
                tmpDir = workDir

                // ------------------------------------------------------------
                // Bộ theo dõi kết quả từng đoạn (dùng chung cho warm-up và các
                // đoạn thật). Mỗi utteranceId chỉ được ghi nhận ĐÚNG 1 lần (được
                // remove khỏi map ngay khi có callback đầu tiên) nên callback
                // trùng lặp hoặc đến muộn của phiên trước bị bỏ qua tự động.
                // onStop = bị xoá khỏi hàng đợi bởi bộ đọc khác dùng chung engine
                // (TalkBack dùng QUEUE_DESTROY) -> sẽ được xếp lại, không báo lỗi.
                // ------------------------------------------------------------
                val pendingIds = ConcurrentHashMap<String, Int>()
                val events = LinkedBlockingQueue<IntArray>()
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    private fun post(id: String?, outcome: Int) {
                        val key = id ?: return
                        val index = pendingIds.remove(key) ?: return
                        events.offer(intArrayOf(index, outcome))
                    }
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { post(id, OUTCOME_DONE) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { post(id, OUTCOME_ERROR) }
                    override fun onError(id: String?, errorCode: Int) { post(id, OUTCOME_ERROR) }
                    override fun onStop(id: String?, interrupted: Boolean) { post(id, OUTCOME_STOPPED) }
                })

                // "Làm nóng" engine: tổng hợp 1 đoạn cực ngắn ra file tạm rồi bỏ kết
                // quả. Chỉ chờ tối đa 1,5 giây (bản cũ chờ tới 5 giây và dùng văn bản
                // chỉ gồm dấu chấm: nhiều engine không gọi callback cho văn bản như
                // vậy nên lần lưu nào cũng phí trọn 5 giây).
                val warmupFile = File(workDir, "warmup.wav")
                val warmupId = newUtteranceId("warmup")
                pendingIds[warmupId] = -1
                try {
                    val warmupResult = engine.synthesizeToFile("Xin chào.", Bundle(), warmupFile, warmupId)
                    if (warmupResult == TextToSpeech.SUCCESS) {
                        events.poll(1500L, TimeUnit.MILLISECONDS)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Làm nóng engine tổng hợp thất bại (bỏ qua, không ảnh hưởng file thật): ${t.message}")
                } finally {
                    pendingIds.remove(warmupId)
                    events.clear()
                    try { warmupFile.delete() } catch (_: Throwable) {}
                }

                // ------------------------------------------------------------
                // Tổng hợp theo kiểu "băng chuyền" (pipeline): luôn xếp sẵn tối đa
                // FILE_PIPELINE_DEPTH đoạn trong hàng đợi của engine, mỗi khi xong
                // 1 đoạn thì bù ngay 1 đoạn mới. Engine không bao giờ phải ngồi
                // chờ ta nhận callback rồi mới gửi đoạn kế như bản cũ.
                // ------------------------------------------------------------
                // Tốc độ chậm -> âm thanh mỗi đoạn dài hơn tỉ lệ nghịch (0.5x = gấp đôi). Với
                // engine tổng hợp gần theo thời gian thực, mức chờ cố định 90 giây sẽ bị
                // hết hạn oan ở tốc độ chậm rồi báo "không phản hồi". Chỉ nới khi rate < 1.
                val eventTimeoutSec = (FILE_EVENT_TIMEOUT_SEC / rate.coerceIn(0.25f, 1.0f)).toLong()
                val errorAttempts = IntArray(total)
                val preemptAttempts = IntArray(total)
                val retryQueue = java.util.ArrayDeque<Int>()
                var nextToSubmit = 0
                var inFlight = 0
                var doneCount = 0
                var failMessage: String? = null

                fun chunkFileOf(index: Int): File = File(workDir, "chunk_%05d.wav".format(index))

                fun submit(index: Int): Boolean {
                    val file = chunkFileOf(index)
                    try { file.delete() } catch (_: Throwable) {}
                    val uid = newUtteranceId("file_$index")
                    pendingIds[uid] = index
                    val r = try {
                        engine.synthesizeToFile(chunks[index], Bundle(), file, uid)
                    } catch (t: Throwable) {
                        Log.e(TAG, "synthesizeToFile ném lỗi ở đoạn ${index + 1}: ${t.message}", t)
                        TextToSpeech.ERROR
                    }
                    if (r != TextToSpeech.SUCCESS) {
                        pendingIds.remove(uid)
                        return false
                    }
                    return true
                }

                // Báo tiến độ cho JS để người dùng thấy app đang chạy (không "đơ").
                fun reportProgress() {
                    try {
                        val p = JSObject()
                        p.put("done", doneCount)
                        p.put("total", total)
                        notifyListeners("saveProgress", p)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Không gửi được sự kiện saveProgress: ${t.message}")
                    }
                }

                // Nghỉ có thể bị ngắt; trả false nếu luồng bị huỷ giữa chừng.
                fun pause(ms: Long): Boolean {
                    return try {
                        Thread.sleep(ms)
                        true
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        false
                    }
                }

                reportProgress()

                while (doneCount < total && failMessage == null) {
                    // 1) Bù cho đủ FILE_PIPELINE_DEPTH đoạn đang xếp hàng trong engine.
                    while (inFlight < FILE_PIPELINE_DEPTH && failMessage == null) {
                        val index = when {
                            !retryQueue.isEmpty() -> retryQueue.pollFirst() ?: -1
                            nextToSubmit < total -> nextToSubmit++
                            else -> -1
                        }
                        if (index < 0) break
                        if (submit(index)) {
                            inFlight++
                        } else {
                            // Engine từ chối nhận ngay (thường do dịch vụ đang kết nối lại).
                            errorAttempts[index]++
                            if (errorAttempts[index] > MAX_ERROR_RETRIES) {
                                failMessage = "Bộ máy đọc từ chối nhận đoạn văn bản thứ ${index + 1}/$total."
                            } else {
                                retryQueue.addFirst(index)
                                if (!pause(400L * errorAttempts[index])) failMessage = "Quá trình tạo file bị gián đoạn."
                                break
                            }
                        }
                    }
                    if (failMessage != null) break

                    if (inFlight == 0) {
                        // Không còn gì đang chạy: hoặc vừa có đoạn cần gửi lại (vòng sau sẽ
                        // gửi), hoặc — không thể xảy ra bình thường — đã mất dấu đoạn nào đó.
                        if (retryQueue.isEmpty() && nextToSubmit >= total && doneCount < total) {
                            failMessage = "Quá trình tạo file bị gián đoạn bất thường. Vui lòng thử lại."
                        }
                        continue
                    }

                    // 2) Chờ đúng 1 kết quả (engine xử lý tuần tự nên mỗi lần chỉ xong 1 đoạn).
                    val event = try {
                        events.poll(eventTimeoutSec, TimeUnit.SECONDS)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        failMessage = "Quá trình tạo file bị gián đoạn."
                        null
                    }
                    if (failMessage != null) break

                    if (event == null) {
                        // Engine im lặng quá lâu: dọn hàng đợi của ta rồi xếp lại toàn bộ
                        // các đoạn đang chờ (mỗi đoạn được tính 1 lần thử lại).
                        val stuck = ArrayList<Int>(pendingIds.values)
                        pendingIds.clear()
                        try { engine.stop() } catch (_: Throwable) {}
                        events.clear()
                        inFlight = 0
                        for (idx in stuck) {
                            errorAttempts[idx]++
                            if (errorAttempts[idx] > MAX_ERROR_RETRIES) {
                                failMessage = "Bộ máy đọc không phản hồi ở đoạn văn bản thứ ${idx + 1}/$total."
                            } else {
                                retryQueue.addLast(idx)
                            }
                        }
                        continue
                    }

                    inFlight--
                    val index = event[0]
                    when (event[1]) {
                        OUTCOME_DONE -> {
                            val f = chunkFileOf(index)
                            if (f.exists() && f.length() > 0L) {
                                doneCount++
                                reportProgress()
                            } else {
                                // Engine báo xong nhưng không có dữ liệu -> coi như lỗi, thử lại.
                                errorAttempts[index]++
                                if (errorAttempts[index] > MAX_ERROR_RETRIES) {
                                    failMessage = "Không thể tổng hợp đoạn văn bản thứ ${index + 1}/$total."
                                } else {
                                    retryQueue.addLast(index)
                                }
                            }
                        }
                        OUTCOME_STOPPED -> {
                            // Bị bộ đọc khác (TalkBack...) chen ngang: xếp lại đúng đoạn này
                            // sau một khoảng nghỉ ngắn để bộ đọc kia nói xong.
                            preemptAttempts[index]++
                            if (preemptAttempts[index] > MAX_PREEMPT_RETRIES) {
                                failMessage = "Bộ đọc đang bận phục vụ ứng dụng khác (ví dụ TalkBack) quá lâu. Vui lòng thử lại sau."
                            } else {
                                retryQueue.addLast(index)
                                if (!pause(minOf(250L * preemptAttempts[index], 1500L))) failMessage = "Quá trình tạo file bị gián đoạn."
                            }
                        }
                        else -> {
                            errorAttempts[index]++
                            if (errorAttempts[index] > MAX_ERROR_RETRIES) {
                                failMessage = "Không thể tổng hợp đoạn văn bản thứ ${index + 1}/$total."
                            } else {
                                retryQueue.addLast(index)
                                if (!pause(400L * errorAttempts[index])) failMessage = "Quá trình tạo file bị gián đoạn."
                            }
                        }
                    }
                }

                if (failMessage != null) {
                    // Thất bại: xoá sạch hàng đợi còn sót trong engine (nếu không engine vẫn
                    // tiếp tục tổng hợp vào các file tạm sắp bị xoá) rồi báo lỗi.
                    pendingIds.clear()
                    try { engine.stop() } catch (_: Throwable) {}
                    call.reject(failMessage)
                    return@runOnExecutor
                }

                // Ghép các đoạn thành 1 file WAV và GHI THẲNG vào MediaStore (Music/
                // DocDocTTS, Android 10+ không cần xin quyền runtime) — không tạo file
                // ghép trung gian nên bớt 1 lượt ghi + 1 lượt đọc toàn bộ dữ liệu âm
                // thanh (file WAV dài có thể tới hàng chục MB).
                val chunkFiles = (0 until total).map { chunkFileOf(it) }
                val fileName = "doc-van-ban-${System.currentTimeMillis()}.wav"
                val savedUri = saveMergedWavToPublicStorage(chunkFiles, fileName)

                if (savedUri == null) {
                    call.reject("Không thể lưu file âm thanh vào bộ nhớ thiết bị.")
                    return@runOnExecutor
                }

                val ret = JSObject()
                ret.put("uri", savedUri.toString())
                ret.put("fileName", fileName)
                call.resolve(ret)

            } catch (oom: OutOfMemoryError) {
                // Văn bản quá dài / quá nhiều đoạn có thể khiến bước ghép file
                // tốn nhiều bộ nhớ. Bắt riêng OOM để trả lỗi rõ ràng thay vì để
                // tiến trình bị hệ điều hành giết (crash im lặng).
                Log.e(TAG, "Hết bộ nhớ khi tạo file âm thanh", oom)
                call.reject("Văn bản quá dài khiến thiết bị hết bộ nhớ khi xử lý. Vui lòng thử với đoạn văn bản ngắn hơn.")
            } catch (io: IOException) {
                Log.e(TAG, "Lỗi I/O khi tạo file âm thanh: ${io.message}", io)
                call.reject("Lỗi khi ghi file âm thanh: ${io.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi tạo file âm thanh: ${e.message}", e)
                call.reject("Lỗi khi tạo file âm thanh: ${e.message}", e)
            } finally {
                // Dọn dẹp file/thư mục tạm nếu còn sót lại do lỗi giữa chừng,
                // tránh rác tích lũy trong cache theo thời gian.
                try { tmpDir?.deleteRecursively() } catch (_: Throwable) {}
                releaseWakeLock()
            }
        }
    }

    // ------------------------------------------------------------------
    // Lấy danh sách toàn bộ giọng nói có sẵn trên máy (dùng để đổ vào dropdown)
    // ------------------------------------------------------------------
    @PluginMethod
    fun getVoices(call: PluginCall) {
        if (!ensureReadyOrReject(call)) return
        try {
            val engine = tts
            if (engine == null) {
                call.reject("Bộ máy Text-to-Speech chưa sẵn sàng. Vui lòng thử lại sau vài giây.")
                return
            }
            val arr = JSArray()
            engine.voices?.forEach { v ->
                val obj = JSObject()
                obj.put("name", v.name)
                obj.put("lang", v.locale.toLanguageTag())
                arr.put(obj)
            }
            val ret = JSObject()
            ret.put("voices", arr)
            call.resolve(ret)
        } catch (t: Throwable) {
            Log.e(TAG, "Lỗi khi lấy danh sách giọng đọc: ${t.message}", t)
            call.reject("Không lấy được danh sách giọng đọc: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // Lấy TOÀN BỘ danh sách BỘ ĐỌC (TTS engine) cài trên máy — ví dụ Google
    // Text-to-speech, Samsung TTS, v.v. — kèm theo TOÀN BỘ GIỌNG ĐỌC có
    // trong TỪNG bộ đọc đó (không chỉ giọng của engine mặc định).
    //
    // Android không cho lấy voices() của 1 engine không active chỉ bằng 1
    // instance TextToSpeech. Vì vậy với mỗi engine liệt kê được, ta phải
    // khởi tạo TẠM 1 instance TextToSpeech riêng trỏ đúng gói (package) của
    // engine đó, đợi init xong, đọc voices(), rồi shutdown() ngay để không
    // giữ tài nguyên/rò rỉ. Việc này chạy tuần tự trên executor riêng (không
    // phải luồng chính) vì có thể mất vài giây nếu máy cài nhiều bộ đọc.
    // ------------------------------------------------------------------
    @PluginMethod
    fun getEnginesWithVoices(call: PluginCall) {
        if (!ensureReadyOrReject(call)) return
        runOnExecutor(call) {
            val defaultEngine = tts
            if (defaultEngine == null) {
                call.reject("Bộ máy Text-to-Speech chưa sẵn sàng. Vui lòng thử lại sau vài giây.")
                return@runOnExecutor
            }
            try {
                val engineInfos = try {
                    defaultEngine.engines ?: emptyList()
                } catch (t: Throwable) {
                    Log.w(TAG, "Không lấy được danh sách bộ đọc: ${t.message}")
                    emptyList()
                }

                val resultArr = JSArray()
                for (info in engineInfos) {
                    val voicesArr = JSArray()
                    var tempTts: TextToSpeech? = null
                    try {
                        val latch = CountDownLatch(1)
                        var initOk = false
                        try {
                            tempTts = TextToSpeech(context, { status ->
                                initOk = (status == TextToSpeech.SUCCESS)
                                latch.countDown()
                            }, info.name)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Không khởi tạo được bộ đọc tạm '${info.name}': ${t.message}")
                            latch.countDown()
                        }
                        // Chờ tối đa 5 giây cho MỖI engine — tránh 1 engine lỗi/chậm
                        // làm treo toàn bộ danh sách.
                        try {
                            latch.await(5, TimeUnit.SECONDS)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                        if (initOk) {
                            try {
                                tempTts?.voices?.forEach { v ->
                                    val vObj = JSObject()
                                    vObj.put("name", v.name)
                                    vObj.put("lang", v.locale.toLanguageTag())
                                    voicesArr.put(vObj)
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "Không lấy được voices của bộ đọc '${info.name}': ${t.message}")
                            }
                        }
                    } finally {
                        // Luôn shutdown instance tạm, kể cả khi init lỗi/timeout,
                        // để không giữ tài nguyên hệ thống.
                        try { tempTts?.stop() } catch (_: Throwable) {}
                        try { tempTts?.shutdown() } catch (_: Throwable) {}
                    }

                    val engObj = JSObject()
                    engObj.put("name", info.name)
                    engObj.put("label", info.label ?: info.name)
                    engObj.put("voices", voicesArr)
                    resultArr.put(engObj)
                }

                val ret = JSObject()
                ret.put("engines", resultArr)
                ret.put("activeEngine", activeEngineName ?: "")
                call.resolve(ret)
            } catch (t: Throwable) {
                Log.e(TAG, "Lỗi khi lấy danh sách bộ đọc/giọng đọc: ${t.message}", t)
                call.reject("Không lấy được danh sách bộ đọc: ${t.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // Chuyển bộ đọc (engine) đang dùng cho speak()/synthesizeToFile() sang
    // đúng bộ đọc mà người dùng chọn trong dropdown. Tắt engine cũ, khởi
    // tạo engine mới, CHỜ tới khi engine mới init xong rồi mới trả kết quả
    // cho JS — tránh trường hợp người dùng bấm "Đọc" ngay sau khi vừa đổi
    // bộ đọc mà engine mới chưa kịp sẵn sàng.
    // engineName rỗng ("") = quay lại engine mặc định của hệ thống.
    // ------------------------------------------------------------------
    @PluginMethod
    fun setEngine(call: PluginCall) {
        val engineName = call.getString("engineName") ?: ""
        runOnExecutor(call) {
            val oldTts = tts
            try {
                // Phòng thủ: nếu vì lý do nào đó vẫn còn giữ AudioFocus từ phiên
                // đọc trước (lẽ ra JS đã gọi stopSpeaking() trước khi đổi engine),
                // nhả ra trước khi tạo engine mới để không giữ focus "treo".
                abandonSpeechAudioFocus()
                val latch = CountDownLatch(1)
                var initStatus = TextToSpeech.ERROR
                val newTts = if (engineName.isBlank()) {
                    TextToSpeech(context) { status -> initStatus = status; latch.countDown() }
                } else {
                    TextToSpeech(context, { status -> initStatus = status; latch.countDown() }, engineName)
                }
                val finished = try {
                    latch.await(10, TimeUnit.SECONDS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
                if (!finished || initStatus != TextToSpeech.SUCCESS) {
                    try { newTts.shutdown() } catch (_: Throwable) {}
                    call.reject("Không thể chuyển sang bộ đọc đã chọn (mã lỗi $initStatus).")
                    return@runOnExecutor
                }

                // Chỉ thay tham chiếu 'tts' SAU KHI engine mới đã sẵn sàng, để
                // các lệnh speak()/synthesizeToFile() đang chờ trong executor
                // (chạy tuần tự, cùng 1 luồng) luôn thấy engine hợp lệ.
                tts = newTts
                activeEngineName = engineName.ifBlank { null }
                configureEngineAudioAttributes(newTts)

                try { oldTts?.stop() } catch (_: Throwable) {}
                try { oldTts?.shutdown() } catch (_: Throwable) {}

                call.resolve()
            } catch (t: Throwable) {
                Log.e(TAG, "Lỗi khi chuyển bộ đọc: ${t.message}", t)
                call.reject("Lỗi khi chuyển bộ đọc: ${t.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // Đọc văn bản ra loa TRỰC TIẾP bằng TextToSpeech gốc của Android
    // (dùng thay cho Web Speech API vì WebView có thể không hỗ trợ đầy đủ)
    // ------------------------------------------------------------------
    @Volatile private var speakCancelled = false

    // Bộ đếm "lệnh Dừng": tăng 1 mỗi khi stopSpeaking()/synthesizeToFile() được gọi.
    // speak() ghi nhớ giá trị này ngay lúc được gọi; tới khi executor thật sự chạy
    // tác vụ mà giá trị đã khác nghĩa là người dùng đã bấm Dừng/Lưu SAU khi lệnh đọc
    // này được gửi -> bỏ lệnh đọc "cũ" đó. Bản cũ đặt lại speakCancelled = false ở
    // đầu tác vụ nên lệnh Dừng đến sớm bị nuốt mất và app vẫn đọc hết văn bản (dễ
    // gặp khi đổi tốc độ/giọng liên tiếp hoặc khi executor đang bận việc khác).
    private val stopEpoch = AtomicInteger(0)

    // Giữ tham chiếu tới latch đang chờ, để stopSpeaking() có thể giải phóng
    // ngay lập tức thay vì để executor bị kẹt tới khi hết hạn chờ (fix bug:
    // tts.stop() không đảm bảo callback onDone/onError sẽ được gọi).
    @Volatile private var currentLatch: CountDownLatch? = null

    @PluginMethod
    fun speak(call: PluginCall) {
        // "chunks": mảng các câu (JS đã tách sẵn bằng splitIntoChunks, để biết
        // chính xác đang đọc tới câu nào mà "đọc tiếp" đúng chỗ sau khi Dừng).
        // Vẫn nhận "text" đơn lẻ để tương thích ngược nếu có bản JS cũ hơn
        // chưa gửi mảng.
        val chunksArray = call.getArray("chunks")
        val sentences: List<String> = if (chunksArray != null && chunksArray.length() > 0) {
            (0 until chunksArray.length()).map { i -> chunksArray.optString(i, "") }
        } else {
            val text = call.getString("text")
            if (text.isNullOrBlank()) {
                call.reject("Thiếu văn bản cần đọc (chunks hoặc text).")
                return
            }
            listOf(text)
        }
        if (sentences.isEmpty()) {
            call.reject("Danh sách câu cần đọc rỗng.")
            return
        }
        val startIndex = (call.getInt("startIndex") ?: 0).coerceIn(0, sentences.size - 1)
        if (!ensureReadyOrReject(call)) return

        val voiceName = call.getString("voiceName") ?: ""
        val lang = call.getString("lang") ?: "vi-VN"
        val rate = readFloatParam(call, "rate", 1.0f)
        val pitch = readFloatParam(call, "pitch", 1.0f)
        val warmup = call.getBoolean("warmup") ?: true
        val epochAtCall = stopEpoch.get()

        runOnExecutor(call) {
            // Đã có lệnh Dừng/Lưu file SAU khi lệnh đọc này được gửi (lúc này mới tới
            // lượt chạy): coi như đã bị hủy, KHÔNG đọc và KHÔNG đặt lại speakCancelled.
            if (stopEpoch.get() != epochAtCall) {
                val stale = JSObject()
                stale.put("finished", false)
                stale.put("cancelled", true)
                stale.put("failed", false)
                stale.put("failReason", "")
                stale.put("lastIndex", startIndex)
                call.resolve(stale)
                return@runOnExecutor
            }
            val engine = tts
            if (engine == null) {
                call.reject("Bộ máy Text-to-Speech chưa sẵn sàng. Vui lòng thử lại sau vài giây.")
                return@runOnExecutor
            }
            try {
                speakCancelled = false
                applyVoiceSettings(engine, voiceName, lang, rate, pitch)

                if (warmup) {
                    // Xin AudioFocus TƯỜNG MINH và giữ CPU thức (WakeLock) trong
                    // suốt phiên đọc — cả 2 đều CHỜ xong trước khi phát bất kỳ
                    // mẫu âm thanh nào, khắc phục việc mất tiếng đầu VÀ việc CPU
                    // bị Doze làm treo giữa chừng khi khóa màn hình.
                    requestSpeechAudioFocus()
                    acquireWakeLock()
                    try {
                        engine.playSilentUtterance(300L, TextToSpeech.QUEUE_FLUSH, null)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Không làm nóng được audio pipeline (bỏ qua, không chặn luồng đọc): ${t.message}")
                    }
                }

                // ------------------------------------------------------------
                // QUAN TRỌNG (đã sửa lỗi kiến trúc): đọc TOÀN BỘ các câu còn lại
                // NGAY TRONG MỘT LẦN GỌI này — KHÔNG trả quyền điều khiển về JS
                // rồi chờ JS gọi lại speak() cho từng câu. Gọi lại theo từng câu
                // (round-trip JS <-> Kotlin) khiến hàng đợi âm thanh của engine
                // bị RỖNG giữa 2 câu trong lúc chờ round-trip, buộc audio route
                // phải khởi động lại y như câu đầu tiên -> gây mất tiếng đầu +
                // khựng LẶP LẠI ở MỌI câu (đúng lỗi đang gặp). Đọc liền mạch
                // trong 1 vòng lặp Kotlin duy nhất mới đảm bảo QUEUE_ADD nối
                // liền các câu mà không có khoảng trống chờ bridge.
                // Vị trí đang đọc được báo về JS qua notifyListeners("speakProgress")
                // bên dưới, JS chỉ cần lắng nghe để cập nhật tiến độ, không cần tự
                // gọi lại từng câu.
                // ------------------------------------------------------------
                var lastIndexReached = startIndex
                // true nếu phải dừng vì engine liên tục lỗi / liên tục bị chen ngang.
                var failed = false
                var failReason = ""

                // Gom mọi đoạn con của các câu còn lại thành 1 danh sách phẳng, đúng thứ
                // tự đọc, để có thể xếp trước đoạn kế tiếp vào hàng đợi của engine.
                val items = ArrayList<SpeakItem>()
                for (sentenceIndex in startIndex until sentences.size) {
                    val parts = splitIntoChunks(sentences[sentenceIndex], SAFE_CHUNK_LEN)
                    for ((subIdx, partText) in parts.withIndex()) {
                        // Bỏ đoạn KHÔNG có chữ/số nào (chỉ dấu câu/ký hiệu như "-----", "***"):
                        // nhiều engine không phát âm gì và cũng KHÔNG gọi onDone/onError cho
                        // đoạn như vậy, khiến vòng đọc đứng chờ hết thời hạn rồi báo lỗi liên
                        // tục và dừng cả bài ở đúng chỗ đó.
                        if (partText.none { it.isLetterOrDigit() }) continue
                        items.add(SpeakItem(sentenceIndex, subIdx, partText))
                    }
                }

                // utteranceId đang chờ kết quả -> (latch, outcome). MỘT listener dùng
                // chung cho cả phiên (bản cũ gắn listener riêng cho từng đoạn, nên không
                // thể có 2 đoạn cùng nằm trong hàng đợi). compareAndSet đảm bảo mỗi lần
                // gửi chỉ có ĐÚNG MỘT kết quả được ghi nhận (callback đến từ luồng
                // binder, có thể đồng thời/trùng lặp); id không còn trong map thì bỏ qua.
                val slots = ConcurrentHashMap<String, SpeakSlot>()
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    private fun finish(id: String?, result: Int) {
                        val key = id ?: return
                        val slot = slots[key] ?: return
                        if (slot.outcome.compareAndSet(OUTCOME_PENDING, result)) slot.latch.countDown()
                    }
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { finish(id, OUTCOME_DONE) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { finish(id, OUTCOME_ERROR) }
                    override fun onError(id: String?, errorCode: Int) { finish(id, OUTCOME_ERROR) }
                    // Bị dừng/xoá khỏi hàng đợi: do CHÍNH ta (stopSpeaking) hoặc do bộ đọc
                    // khác dùng chung engine (TalkBack...) chen ngang bằng QUEUE_DESTROY.
                    override fun onStop(id: String?, interrupted: Boolean) { finish(id, OUTCOME_STOPPED) }
                })

                // Đoạn đã nằm trong hàng đợi của engine (theo vị trí trong `items`).
                val queued = arrayOfNulls<SpeakSlot>(items.size)

                // Gửi đoạn thứ `at` vào hàng đợi engine (QUEUE_ADD). null = engine từ chối ngay.
                fun submitItem(at: Int): SpeakSlot? {
                    val item = items[at]
                    val uid = newUtteranceId("speak_${item.sentenceIndex}_${item.subIdx}")
                    val slot = SpeakSlot(uid, CountDownLatch(1), AtomicInteger(OUTCOME_PENDING))
                    slots[uid] = slot
                    val r = try {
                        engine.speak(item.text, TextToSpeech.QUEUE_ADD, Bundle(), uid)
                    } catch (t: Throwable) {
                        Log.e(TAG, "speak() ném lỗi ở câu ${item.sentenceIndex}, đoạn ${item.subIdx}: ${t.message}", t)
                        TextToSpeech.ERROR
                    }
                    if (r != TextToSpeech.SUCCESS) {
                        slots.remove(uid)
                        return null
                    }
                    return slot
                }

                var pos = 0
                var emittedSentence = -1
                var errorRetries = 0
                var preemptRetries = 0
                var lastWakeRefresh = System.currentTimeMillis()

                // Bỏ ghi nhận các đoạn đã xếp hàng ở vị trí `pos` và `pos + 1` (do lỗi/bị
                // chen ngang) để vòng sau gửi lại ĐÚNG từ `pos`, không đọc lệch thứ tự.
                fun dropQueued() {
                    for (k in pos..minOf(pos + 1, items.size - 1)) {
                        queued[k]?.let { slots.remove(it.id) }
                        queued[k] = null
                    }
                }

                // ------------------------------------------------------------
                // ĐỌC NỐI LIỀN: trong lúc đoạn `pos` đang phát, đoạn `pos + 1` đã nằm sẵn
                // trong hàng đợi của engine (QUEUE_ADD). Bản cũ chỉ gửi đoạn kế SAU KHI
                // nhận onDone của đoạn trước, nên giữa 2 đoạn hàng đợi luôn rỗng một
                // khoảng ngắn (chờ callback + đánh thức luồng + gọi binder) -> audio
                // track của engine bị đóng/mở lại -> nghe thấy "khựng" lặp lại mỗi vài
                // giây (rõ hơn ở tốc độ cao vì đoạn ngắn hơn). Nay engine luôn có sẵn việc
                // để làm ngay khi vừa xong đoạn trước. Đặt SPEAK_PREFETCH_NEXT = false để
                // quay về cách cũ (mỗi lần chỉ 1 đoạn trong hàng đợi) nếu cần.
                // Lỗi/bị chen ngang: bỏ phần đã xếp trước rồi gửi lại từ đoạn lỗi, nên
                // thứ tự đọc luôn đúng và không đoạn nào bị bỏ sót hay đọc trùng.
                // ------------------------------------------------------------
                loop@ while (pos < items.size) {
                    if (speakCancelled) break@loop

                    // WakeLock tự hết hạn sau 10 phút trong khi bài dài đọc cả chục phút
                    // (25.000 ký tự ~ 30 phút ở 1.0x, ~60 phút ở 0.5x) -> gia hạn mỗi ~1 phút.
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastWakeRefresh > WAKE_LOCK_REFRESH_MS) {
                        acquireWakeLock()
                        lastWakeRefresh = nowMs
                    }

                    val item = items[pos]
                    lastIndexReached = item.sentenceIndex

                    // Báo cho JS biết ĐANG bắt đầu đọc câu này (để thanh tiến độ và
                    // vị trí "đọc tiếp" luôn khớp với audio thật đang phát).
                    if (item.sentenceIndex != emittedSentence) {
                        emittedSentence = item.sentenceIndex
                        try {
                            val progress = JSObject()
                            progress.put("index", item.sentenceIndex)
                            notifyListeners("speakProgress", progress)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Không gửi được sự kiện speakProgress: ${t.message}")
                        }
                    }

                    // Kiểm tra lại speakCancelled NGAY TRƯỚC khi gọi speak() thật:
                    // stopSpeaking() chạy trên luồng khác nên có 1 khe hở cực hẹp; check
                    // lại giúp không phát thêm câu thừa sau khi người dùng đã bấm Dừng.
                    if (queued[pos] == null) {
                        if (speakCancelled) break@loop
                        queued[pos] = submitItem(pos)
                    }
                    if (SPEAK_PREFETCH_NEXT && queued[pos] != null && pos + 1 < items.size && queued[pos + 1] == null) {
                        if (speakCancelled) break@loop
                        queued[pos + 1] = submitItem(pos + 1)
                    }

                    val slot = queued[pos]
                    var outcome = OUTCOME_ERROR // engine từ chối ngay (slot == null) coi như lỗi
                    if (slot != null) {
                        currentLatch = slot.latch
                        if (speakCancelled) {
                            currentLatch = null
                            break@loop
                        }
                        // Thời gian chờ tối đa tỉ lệ với độ dài đoạn và TỐC ĐỘ ĐỌC: đọc
                        // chậm (0.5x) một đoạn dài có thể mất cả phút, không được coi là
                        // treo. Chỉ để phát hiện engine "im lặng" hẳn.
                        val timeoutSec = 20L + (item.text.length / (5.0f * rate.coerceAtLeast(0.25f))).toLong()
                        val finished = try {
                            slot.latch.await(timeoutSec, TimeUnit.SECONDS)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            false
                        }
                        currentLatch = null
                        if (speakCancelled) break@loop

                        if (!finished && slot.outcome.compareAndSet(OUTCOME_PENDING, OUTCOME_ERROR)) {
                            // Engine không phản hồi gì trong suốt thời gian chờ -> coi là lỗi,
                            // dọn hàng đợi của ta rồi thử lại.
                            try { engine.stop() } catch (_: Throwable) {}
                        }
                        outcome = slot.outcome.get()
                        slots.remove(slot.id)
                    }

                    when (outcome) {
                        OUTCOME_DONE -> {
                            queued[pos] = null
                            errorRetries = 0
                            preemptRetries = 0
                            pos++
                        }
                        OUTCOME_STOPPED -> {
                            // Bị chen ngang (TalkBack...): hàng đợi của ta đã bị chính bộ đọc
                            // kia xoá sạch. KHÔNG gọi engine.stop() ở đây để khỏi cắt lời
                            // của họ. Xếp lại đúng đoạn này sau một khoảng nghỉ ngắn.
                            dropQueued()
                            preemptRetries++
                            if (preemptRetries > MAX_PREEMPT_RETRIES) {
                                failed = true
                                failReason = "preempted"
                                break@loop
                            }
                            if (!sleepUnlessCancelled(minOf(250L * preemptRetries, 1500L))) break@loop
                        }
                        else -> {
                            // Lỗi thật: engine vẫn tiếp tục đọc đoạn đã xếp trước, nên phải
                            // xoá hàng đợi, nếu không đoạn kế sẽ vang lên TRƯỚC đoạn lỗi
                            // được gửi lại (đọc lệch thứ tự).
                            dropQueued()
                            try { engine.stop() } catch (_: Throwable) {}
                            errorRetries++
                            if (errorRetries > MAX_ERROR_RETRIES) {
                                failed = true
                                failReason = "engine_error"
                                break@loop
                            }
                            if (!sleepUnlessCancelled(400L * errorRetries)) break@loop
                        }
                    }
                }

                if (failed) {
                    // Dừng hẳn do lỗi: xoá nốt đoạn đã xếp trước trong engine, nếu không nó
                    // vẫn tự đọc tiếp sau khi ta đã báo thất bại cho người dùng.
                    try { engine.stop() } catch (_: Throwable) {}
                }

                val completedAll = !speakCancelled && !failed && pos >= items.size
                try {
                    val done = JSObject()
                    done.put("cancelled", speakCancelled)
                    done.put("failed", failed)
                    done.put("lastIndex", lastIndexReached)
                    done.put("finished", completedAll)
                    notifyListeners("speakDone", done)
                } catch (t: Throwable) {
                    Log.w(TAG, "Không gửi được sự kiện speakDone: ${t.message}")
                }

                // Trả kết quả THẬT về cho JS (bản cũ trả rỗng nên JS luôn báo "Đã
                // đọc xong" kể cả khi đọc dừng giữa chừng vì lỗi).
                val ret = JSObject()
                ret.put("finished", completedAll)
                ret.put("cancelled", speakCancelled)
                ret.put("failed", failed)
                ret.put("failReason", failReason)
                ret.put("lastIndex", lastIndexReached)
                call.resolve(ret)
            } catch (t: Throwable) {
                Log.e(TAG, "Lỗi khi đọc văn bản: ${t.message}", t)
                // Đoạn đã xếp trước (nếu có) không được tự đọc tiếp sau khi ta báo lỗi.
                try { engine.stop() } catch (_: Throwable) {}
                call.reject("Lỗi khi đọc văn bản: ${t.message}")
            } finally {
                // Luôn nhả WakeLock khi phiên đọc kết thúc (dù xong xuôi, lỗi
                // hay bị stopSpeaking() hủy giữa chừng) — không giữ CPU thức
                // ngoài lúc thật sự cần thiết.
                releaseWakeLock()
                // Nhả AudioFocus cả khi đọc XONG TỰ NHIÊN hoặc dừng do lỗi. Bản cũ chỉ
                // nhả khi bấm Dừng/Lưu/đổi bộ đọc nên sau khi đọc hết bài, app vẫn giữ
                // focus và nhạc/podcast của app khác bị tạm dừng mãi. Phiên đọc kế tiếp
                // (kể cả khi đổi tốc độ giữa chừng) tự xin lại focus ở bước warmup.
                abandonSpeechAudioFocus()
            }
        }
    }

    @PluginMethod
    fun stopSpeaking(call: PluginCall) {
        try {
            stopEpoch.incrementAndGet()
            speakCancelled = true
            tts?.stop()
            // Giải phóng ngay thread đang chờ trong speak(), tránh việc lệnh đọc
            // tiếp theo (xếp hàng sau trên cùng 1 executor) phải chờ tới 30 giây.
            currentLatch?.countDown()
            // Dừng hẳn -> nhả AudioFocus. Phiên đọc TIẾP THEO (bấm Phát lại)
            // sẽ tự gửi warmup=true và xin lại focus + làm nóng từ đầu.
            abandonSpeechAudioFocus()
            call.resolve()
        } catch (t: Throwable) {
            // Dừng đọc không phải là thao tác quan trọng tới mức phải làm app
            // crash nếu có lỗi lạ; báo lỗi nhẹ nhàng cho JS là đủ.
            Log.e(TAG, "Lỗi khi dừng đọc: ${t.message}", t)
            call.reject("Lỗi khi dừng đọc: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // Mở hộp thoại Chia sẻ / Lưu của Android cho file vừa tạo
    // ------------------------------------------------------------------
    @PluginMethod
    fun shareFile(call: PluginCall) {
        val uriString = call.getString("uri")
        if (uriString.isNullOrBlank()) {
            call.reject("Thiếu đường dẫn file (uri).")
            return
        }
        try {
            val uri = Uri.parse(uriString)
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "audio/wav"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(intent, "Lưu / Chia sẻ file âm thanh")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            call.resolve()
        } catch (t: Throwable) {
            Log.e(TAG, "Không thể mở hộp thoại chia sẻ: ${t.message}", t)
            call.reject("Không thể mở hộp thoại chia sẻ: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // Chọn giọng nói + áp dụng tốc độ/cao độ cho TTS engine.
    // Nếu ngôn ngữ yêu cầu không có sẵn trên máy, tự rơi về giọng mặc định
    // của engine thay vì im lặng đọc sai giọng (hoặc đọc lỗi) mà không báo.
    // ------------------------------------------------------------------
    // ------------------------------------------------------------------
    // AudioAttributes DÙNG CHUNG cho cả việc xin AudioFocus lẫn cấu hình
    // chính engine TTS (qua configureEngineAudioAttributes() bên dưới).
    // LÝ DO: nếu AudioFocus xin cho "usage A" nhưng bản thân TextToSpeech
    // engine lại tự phát âm thanh trên "usage B" (mặc định khác nhau tuỳ
    // engine/thiết bị), thì việc "xin giữ loa" và "âm thanh thật sự phát
    // ra" đi trên 2 luồng khác nhau — hệ thống không ưu tiên/ducking đúng
    // audio khác đúng như mong đợi, dễ gây xung đột thiết bị đầu ra (ví dụ
    // nhạc nền không bị hạ âm lượng đúng lúc, hoặc cuộc gọi/thông báo chen
    // ngang không đúng cách). Dùng chung 1 AudioAttributes đảm bảo khớp
    // tuyệt đối giữa 2 phía.
    // ------------------------------------------------------------------
    private val speechAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    // Ép chính engine TTS phát âm thanh đúng theo speechAudioAttributes ở
    // trên, thay vì để engine tự chọn mặc định riêng của nó (có thể khác
    // với AudioAttributes ta dùng để xin AudioFocus).
    private fun configureEngineAudioAttributes(engine: TextToSpeech) {
        try {
            engine.setAudioAttributes(speechAudioAttributes)
        } catch (t: Throwable) {
            Log.w(TAG, "Không đặt được AudioAttributes cho engine (bỏ qua, dùng mặc định của engine): ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // Xin AudioFocus TƯỜNG MINH trước khi phát giọng nói qua loa.
    // Lý do cần cái này: nếu để TextToSpeech tự xin AudioFocus ngầm bên
    // trong lần speak() đầu tiên, việc xin focus + Android thật sự mở
    // route âm thanh (audio route) diễn ra BẤT ĐỒNG BỘ và có độ trễ —
    // trong lúc đó engine đã bắt đầu đẩy mẫu âm thanh đầu tiên vào
    // AudioTrack, dẫn tới vài trăm ms đầu bị "rơi mất" trước khi loa kịp
    // mở. Xin focus tường minh và CHỜ kết quả xong mới phát giúp đường
    // audio đã sẵn sàng trước khi có bất kỳ mẫu âm nào được đẩy ra.
    // ------------------------------------------------------------------
    private fun requestSpeechAudioFocus() {
        if (audioFocusRequest != null) return // đã giữ focus từ trước, khỏi xin lại
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(speechAudioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .build()
            val result = audioManager.requestAudioFocus(request)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                audioFocusRequest = request
            } else {
                Log.w(TAG, "Xin AudioFocus không được cấp ngay (result=$result), vẫn tiếp tục đọc bình thường.")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Lỗi khi xin AudioFocus (bỏ qua, không chặn luồng đọc): ${t.message}")
        }
    }

    private fun abandonSpeechAudioFocus() {
        val request = audioFocusRequest ?: return
        audioFocusRequest = null
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return
            audioManager.abandonAudioFocusRequest(request)
        } catch (t: Throwable) {
            Log.w(TAG, "Lỗi khi nhả AudioFocus (không quan trọng): ${t.message}")
        }
    }

    // Giữ CPU thức (không giữ màn hình sáng) trong lúc đang đọc, để tránh
    // Doze/App Standby của Android làm treo luồng đọc khi người dùng khóa
    // màn hình hoặc chuyển sang app khác giữa chừng. Timeout 10 phút là lưới
    // an toàn cuối cùng, phòng trường hợp release() vì lý do nào đó không
    // được gọi (ví dụ crash) — không giữ WakeLock vô thời hạn.
    private fun acquireWakeLock() {
        try {
            val held = wakeLock
            if (held != null && held.isHeld) {
                // Đang giữ: GIA HẠN. WakeLock không đếm tham chiếu (setReferenceCounted(false))
                // nên acquire(timeout) lần nữa chỉ đặt lại thời hạn, không giữ chồng.
                held.acquire(WAKE_LOCK_TIMEOUT_MS)
                return
            }
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TtsFileSaverPlugin:speak")
            wl.setReferenceCounted(false)
            wl.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLock = wl
        } catch (t: Throwable) {
            Log.w(TAG, "Không giữ được WakeLock (bỏ qua, không chặn luồng đọc): ${t.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (t: Throwable) {
            Log.w(TAG, "Lỗi khi nhả WakeLock (không quan trọng): ${t.message}")
        } finally {
            wakeLock = null
        }
    }

    private fun applyVoiceSettings(engine: TextToSpeech, voiceName: String, lang: String, rate: Float, pitch: Float) {
        var chosenVoice: Voice? = null

        if (voiceName.isNotBlank()) {
            chosenVoice = try {
                engine.voices?.firstOrNull { it.name == voiceName }
            } catch (t: Throwable) {
                null
            }
        }
        if (chosenVoice == null && lang.isNotBlank()) {
            val locale = parseLocale(lang)
            chosenVoice = try {
                engine.voices?.firstOrNull { it.locale.language == locale.language }
            } catch (t: Throwable) {
                null
            }
        }

        if (chosenVoice != null) {
            // Dùng đúng mã trả về của setVoice() (bản cũ bỏ qua nó và luôn coi là
            // SUCCESS nên nhánh dự phòng setLanguage bên dưới không bao giờ chạy).
            val voiceResult = try {
                engine.setVoice(chosenVoice)
            } catch (t: Throwable) {
                Log.w(TAG, "setVoice thất bại: ${t.message}")
                TextToSpeech.ERROR
            }
            if (voiceResult != TextToSpeech.SUCCESS) {
                applyLanguageFallback(engine, lang)
            }
        } else if (lang.isNotBlank()) {
            applyLanguageFallback(engine, lang)
        }

        try {
            engine.setSpeechRate(rate.coerceIn(0.1f, 4.0f))
            engine.setPitch(pitch.coerceIn(0.1f, 2.0f))
        } catch (t: Throwable) {
            Log.w(TAG, "Không áp dụng được tốc độ/cao độ giọng đọc: ${t.message}")
        }
    }

    // Đặt ngôn ngữ, kiểm tra kết quả trả về. Nếu ngôn ngữ không được engine
    // hỗ trợ (LANG_MISSING_DATA hoặc LANG_NOT_SUPPORTED), rơi về tiếng Anh
    // (thường có sẵn trên mọi máy Android) thay vì để engine ở trạng thái
    // không xác định.
    private fun applyLanguageFallback(engine: TextToSpeech, lang: String) {
        val locale = parseLocale(lang)
        val result = try {
            engine.setLanguage(locale)
        } catch (t: Throwable) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Ngôn ngữ '$lang' không được hỗ trợ (mã $result), chuyển sang tiếng Anh mặc định.")
            try {
                engine.setLanguage(Locale.US)
            } catch (t: Throwable) {
                // Nếu cả tiếng Anh cũng lỗi thì để engine dùng ngôn ngữ hiện tại,
                // vẫn tốt hơn là ném lỗi làm hỏng toàn bộ tác vụ đọc/lưu file.
                Log.w(TAG, "Không đặt được cả ngôn ngữ dự phòng: ${t.message}")
            }
        }
    }

    private fun parseLocale(bcp47: String): Locale {
        return try {
            val locale = Locale.forLanguageTag(bcp47)
            if (locale.language.isBlank()) Locale("vi", "VN") else locale
        } catch (e: Exception) {
            Locale("vi", "VN")
        }
    }

    // ------------------------------------------------------------------
    // Tách văn bản dài thành các đoạn ngắn theo câu, an toàn cho TTS
    // ------------------------------------------------------------------
    private fun splitIntoChunks(text: String, maxLen: Int): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()

        val sentenceRegex = Regex("(?<=[.!?…])\\s+|\\n+")
        val rawSentences = normalized.split(sentenceRegex).map { it.trim() }.filter { it.isNotEmpty() }

        val chunks = ArrayList<String>()
        var buffer = StringBuilder()

        fun flushBuffer() {
            if (buffer.isNotEmpty()) {
                chunks.add(buffer.toString())
                buffer = StringBuilder()
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
                if (remaining.isNotEmpty()) buffer.append(remaining)
                continue
            }

            val candidateLen = buffer.length + 1 + sentence.length
            if (candidateLen > maxLen) {
                flushBuffer()
                buffer.append(sentence)
            } else {
                if (buffer.isNotEmpty()) buffer.append(' ')
                buffer.append(sentence)
            }
        }
        flushBuffer()
        return chunks
    }

    // ------------------------------------------------------------------
    // Đọc header của 1 file WAV bằng cách quét thực sự các chunk theo chuẩn
    // RIFF/WAVE, thay vì giả định "data" luôn nằm cố định ở byte 44.
    //
    // LÝ DO: không phải engine TTS nào cũng xuất chunk "fmt " đúng 16 byte.
    // Một số máy (đặc biệt Samsung TTS, một số bản Google TTS) xuất định dạng
    // WAVE_FORMAT_EXTENSIBLE với chunk "fmt " dài 18 hoặc 40 byte, hoặc chèn
    // thêm chunk "fact"/"LIST" trước "data". Nếu cứ giả định offset 44 cố định
    // như bản gốc, phần đầu dữ liệu âm thanh thật sẽ bị cắt nhầm vào giữa chunk
    // khác → file ghép ra bị nhiễu/rè hoặc im lặng, mà KHÔNG hề báo lỗi gì.
    // ------------------------------------------------------------------
    private data class WavInfo(
        val dataOffset: Long,
        val dataSize: Long,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val byteRate: Int,
        val blockAlign: Int,
        // Mã định dạng trong chunk "fmt ": 1 = PCM, 3 = số thực IEEE... (WAVE_FORMAT_EXTENSIBLE
        // 0xFFFE được quy về mã thật trong SubFormat). Cần giữ đúng khi ghép: bản cũ luôn ghi
        // nhãn PCM nên dữ liệu số thực/khác bị gắn nhãn sai -> phát ra tiếng nhiễu.
        val formatTag: Int
    )

    private fun readWavInfo(file: File): WavInfo {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < 12) throw IOException("File WAV không hợp lệ (quá ngắn): ${file.name}")

            val riffHeader = ByteArray(12)
            raf.readFully(riffHeader)
            val riffTag = String(riffHeader, 0, 4, Charsets.US_ASCII)
            val waveTag = String(riffHeader, 8, 4, Charsets.US_ASCII)
            if (riffTag != "RIFF" || waveTag != "WAVE") {
                throw IOException("File không đúng định dạng WAV/RIFF: ${file.name}")
            }

            var sampleRate = 22050
            var channels = 1
            var bitsPerSample = 16
            var byteRate = sampleRate * channels * bitsPerSample / 8
            var blockAlign = channels * bitsPerSample / 8
            var formatTag = 1
            var dataOffset = -1L
            var dataSize = -1L

            while (raf.filePointer + 8 <= length) {
                val chunkHeader = ByteArray(8)
                raf.readFully(chunkHeader)
                val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                var chunkSize = readInt32LE(chunkHeader, 4).toLong() and 0xFFFFFFFFL
                // Phòng file bị ghi thiếu/hỏng phần cuối: không cho chunkSize vượt quá phần còn lại
                val remaining = length - raf.filePointer
                if (chunkSize > remaining) chunkSize = remaining

                when (chunkId) {
                    "fmt " -> {
                        val fmt = ByteArray(chunkSize.toInt())
                        raf.readFully(fmt)
                        if (fmt.size >= 16) {
                            formatTag = readInt16LE(fmt, 0)
                            // WAVE_FORMAT_EXTENSIBLE: mã thật nằm ở 2 byte đầu của SubFormat GUID.
                            if (formatTag == 0xFFFE && fmt.size >= 26) {
                                formatTag = readInt16LE(fmt, 24)
                            }
                            channels = readInt16LE(fmt, 2)
                            sampleRate = readInt32LE(fmt, 4)
                            byteRate = readInt32LE(fmt, 8)
                            blockAlign = readInt16LE(fmt, 12)
                            bitsPerSample = readInt16LE(fmt, 14)
                        }
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = chunkSize
                        raf.seek(raf.filePointer + chunkSize)
                    }
                    else -> {
                        raf.seek(raf.filePointer + chunkSize)
                    }
                }
                // Các chunk RIFF luôn được đệm cho chẵn byte
                if (chunkSize % 2L == 1L && raf.filePointer < length) {
                    raf.seek(raf.filePointer + 1)
                }
            }

            if (dataOffset < 0 || dataSize <= 0) {
                throw IOException("Không tìm thấy dữ liệu âm thanh hợp lệ trong file: ${file.name}")
            }
            return WavInfo(dataOffset, dataSize, sampleRate, channels, bitsPerSample, byteRate, blockAlign, formatTag)
        }
    }

    // ------------------------------------------------------------------
    // Ghi nhiều file WAV nhỏ thành 1 luồng WAV hoàn chỉnh (dùng chung định dạng
    // âm thanh của file đầu tiên; chỉ ghi phần dữ liệu PCM thật của mỗi file,
    // xác định đúng vị trí nhờ readWavInfo ở trên thay vì offset cố định).
    // Đọc/ghi theo luồng bằng bộ đệm cố định 64KB để tránh nạp cả file vào RAM
    // (an toàn với văn bản dài) mà vẫn ít lệnh đọc/ghi hệ thống hơn hẳn bộ đệm 8KB.
    //
    // ĐẢM BẢO TỐC ĐỘ ĐÚNG: header của file ghép chỉ ghi được MỘT tần số lấy mẫu
    // (của đoạn đầu). Đoạn nào lệch định dạng (tần số/số kênh) được đổi về đúng
    // định dạng đó bằng WavPcmConverter thay vì nối thẳng — nối thẳng làm đoạn đó
    // phát NHANH/CHẬM bất thường (bản cũ chỉ ghi cảnh báo rồi vẫn nối). Lệch mà
    // không đổi được (không phải PCM 16-bit mono/stereo) thì báo lỗi rõ ràng,
    // không ghi ra file sai một cách âm thầm.
    // ------------------------------------------------------------------
    private fun isSameWavFormat(a: WavInfo, b: WavInfo): Boolean {
        return a.sampleRate == b.sampleRate &&
            a.channels == b.channels &&
            a.bitsPerSample == b.bitsPerSample &&
            a.formatTag == b.formatTag
    }

    // Số byte dữ liệu mà đoạn `info` sẽ đóng góp vào file ghép (đã căn theo khung
    // và đã tính cả việc đổi định dạng). Dùng để ghi ĐÚNG kích thước vào header
    // trước khi ghi dữ liệu.
    private fun mergedDataBytes(info: WavInfo, first: WavInfo): Long {
        val srcFrameBytes = (info.channels * info.bitsPerSample / 8).coerceAtLeast(1)
        val srcFrames = info.dataSize / srcFrameBytes
        if (isSameWavFormat(info, first)) return srcFrames * srcFrameBytes
        val dstFrameBytes = first.channels * first.bitsPerSample / 8
        return WavPcmConverter.outputFrames(srcFrames, info.sampleRate, first.sampleRate) * dstFrameBytes
    }

    private fun writeMergedWav(files: List<File>, infos: List<WavInfo>, out: java.io.OutputStream) {
        if (files.isEmpty()) throw IOException("Không có đoạn âm thanh nào để ghép.")

        val first = infos[0]
        if (first.sampleRate <= 0 || first.channels <= 0 || first.bitsPerSample <= 0) {
            throw IOException(
                "Đoạn âm thanh đầu tiên có định dạng không hợp lệ " +
                    "(${first.sampleRate}Hz/${first.channels}ch/${first.bitsPerSample}bit)."
            )
        }

        // Kiểm tra TRƯỚC khi ghi bất kỳ byte nào: đoạn lệch định dạng phải đổi được.
        for (i in 1 until infos.size) {
            val cur = infos[i]
            if (isSameWavFormat(cur, first)) continue
            val convertible = cur.formatTag == 1 && first.formatTag == 1 &&
                cur.bitsPerSample == 16 && first.bitsPerSample == 16 &&
                cur.channels in 1..2 && first.channels in 1..2 && cur.sampleRate > 0
            if (!convertible) {
                throw IOException(
                    "Đoạn ${i + 1} có định dạng âm thanh " +
                        "(${cur.sampleRate}Hz/${cur.channels}ch/${cur.bitsPerSample}bit/mã ${cur.formatTag}) khác đoạn đầu " +
                        "(${first.sampleRate}Hz/${first.channels}ch/${first.bitsPerSample}bit/mã ${first.formatTag}) " +
                        "và không thể tự đổi. Hãy thử giọng đọc hoặc bộ đọc khác."
                )
            }
            Log.w(
                TAG,
                "Đoạn ${i + 1} lệch định dạng (${cur.sampleRate}Hz/${cur.channels}ch) so với đoạn đầu " +
                    "(${first.sampleRate}Hz/${first.channels}ch): tự động đổi để giữ đúng tốc độ."
            )
        }

        val totalDataSize = infos.sumOf { mergedDataBytes(it, first) }
        val frameBytes = first.channels * first.bitsPerSample / 8
        // byteRate/blockAlign tính lại từ chính định dạng sẽ ghi ra, không tin các
        // trường tương ứng trong header do engine sinh ra.
        writeWavHeader(
            out, totalDataSize, first.sampleRate, first.channels, first.bitsPerSample,
            first.sampleRate * frameBytes, frameBytes, first.formatTag
        )

        val buffer = ByteArray(65536)
        files.forEachIndexed { i, f ->
            val info = infos[i]
            if (isSameWavFormat(info, first)) {
                // Cùng định dạng: chép thẳng, chỉ lấy số byte đã căn theo khung.
                var remaining = mergedDataBytes(info, first)
                RandomAccessFile(f, "r").use { raf ->
                    raf.seek(info.dataOffset)
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val n = raf.read(buffer, 0, toRead)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        remaining -= n
                    }
                }
                if (remaining > 0) {
                    throw IOException("File tạm của đoạn ${i + 1} bị cụt, không đủ dữ liệu như khai báo.")
                }
            } else {
                // Lệch định dạng: đọc cả đoạn (chỉ vài MB) rồi đổi về định dạng của đoạn đầu.
                if (info.dataSize > 64L * 1024L * 1024L) {
                    throw IOException("Đoạn âm thanh ${i + 1} quá lớn để đổi định dạng.")
                }
                val srcFrameBytes = info.channels * 2
                val srcFrames = (info.dataSize / srcFrameBytes).toInt()
                val src = ByteArray(srcFrames * srcFrameBytes)
                RandomAccessFile(f, "r").use { raf ->
                    raf.seek(info.dataOffset)
                    raf.readFully(src)
                }
                WavPcmConverter.convertPcm16(
                    src, srcFrames, info.channels, info.sampleRate,
                    first.channels, first.sampleRate, out
                )
            }
        }
    }

    private fun writeWavHeader(
        out: java.io.OutputStream,
        dataSize: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        byteRate: Int,
        blockAlign: Int,
        formatTag: Int
    ) {
        // WAV chuẩn (RIFF) dùng trường 32-bit cho kích thước, tức tối đa ~4GB.
        // Với văn bản 25.000 ký tự ở tốc độ đọc bình thường, dữ liệu PCM sinh
        // ra không thể chạm ngưỡng này, nhưng vẫn chặn tường minh để báo lỗi
        // rõ ràng thay vì ghi ra 1 file WAV hỏng (giá trị âm/tràn số) nếu có
        // trường hợp bất thường nào đó phát sinh văn bản khổng lồ.
        if (dataSize > 0xFFFFFFFFL - 36L) {
            throw IOException("Dữ liệu âm thanh quá lớn để ghi thành 1 file WAV (vượt giới hạn 4GB).")
        }

        val totalDataLen = dataSize + 36
        val header = ByteArray(44)

        writeAscii(header, 0, "RIFF")
        writeInt32LE(header, 4, totalDataLen.toInt())
        writeAscii(header, 8, "WAVE")
        writeAscii(header, 12, "fmt ")
        writeInt32LE(header, 16, 16) // Subchunk1Size cho PCM
        writeInt16LE(header, 20, formatTag) // AudioFormat: 1 = PCM (hoặc đúng mã của dữ liệu gốc)
        writeInt16LE(header, 22, channels)
        writeInt32LE(header, 24, sampleRate)
        writeInt32LE(header, 28, byteRate)
        writeInt16LE(header, 32, blockAlign)
        writeInt16LE(header, 34, bitsPerSample)
        writeAscii(header, 36, "data")
        writeInt32LE(header, 40, dataSize.toInt())

        out.write(header)
    }

    private fun readInt16LE(b: ByteArray, offset: Int): Int {
        return (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readInt32LE(b: ByteArray, offset: Int): Int {
        return (b[offset].toInt() and 0xFF) or
                ((b[offset + 1].toInt() and 0xFF) shl 8) or
                ((b[offset + 2].toInt() and 0xFF) shl 16) or
                ((b[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeInt16LE(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value and 0xFF).toByte()
        b[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeInt32LE(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value and 0xFF).toByte()
        b[offset + 1] = ((value shr 8) and 0xFF).toByte()
        b[offset + 2] = ((value shr 16) and 0xFF).toByte()
        b[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeAscii(b: ByteArray, offset: Int, text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        System.arraycopy(bytes, 0, b, offset, bytes.size)
    }

    // ------------------------------------------------------------------
    // Ghép các đoạn WAV và lưu THẲNG vào bộ nhớ công khai của thiết bị
    // (Music/DocDocTTS) qua MediaStore (Android 10+, không cần xin quyền runtime),
    // không qua file ghép trung gian.
    // ------------------------------------------------------------------
    private fun saveMergedWavToPublicStorage(chunkFiles: List<File>, displayName: String): Uri? {
        // Đọc thông tin header TRƯỚC khi tạo bản ghi MediaStore: nếu có đoạn hỏng thì
        // báo lỗi ngay, không để lại 1 bản ghi rác trong thư viện nhạc của người dùng.
        val infos = chunkFiles.map { readWavInfo(it) }

        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/DocDocTTS")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values) ?: return null

        var writeOk = false
        try {
            resolver.openOutputStream(itemUri)?.let { rawOut ->
                java.io.BufferedOutputStream(rawOut, 65536).use { out ->
                    writeMergedWav(chunkFiles, infos, out)
                }
                writeOk = true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Lỗi khi ghi dữ liệu vào MediaStore: ${t.message}", t)
        }

        if (!writeOk) {
            // Ghi thất bại giữa chừng: xoá bản ghi "pending" rác thay vì để lại
            // 1 file 0-byte hiển thị trong Music của người dùng.
            try { resolver.delete(itemUri, null, null) } catch (_: Throwable) {}
            return null
        }

        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        try {
            resolver.update(itemUri, values, null, null)
        } catch (t: Throwable) {
            Log.e(TAG, "Lỗi khi hoàn tất bản ghi MediaStore: ${t.message}", t)
            return null
        }
        return itemUri
    }

    override fun handleOnDestroy() {
        super.handleOnDestroy()
        try { tts?.stop() } catch (_: Throwable) {}
        try { tts?.shutdown() } catch (_: Throwable) {}
        try { executor.shutdownNow() } catch (_: Throwable) {}
        try { abandonSpeechAudioFocus() } catch (_: Throwable) {}
        try { releaseWakeLock() } catch (_: Throwable) {}
    }
}

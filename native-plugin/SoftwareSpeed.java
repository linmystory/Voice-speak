package com.docdoc.app;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

// ============================================================================
// SoftwareSpeed.java
//
// Đổi TỐC ĐỘ giọng đọc bằng phần mềm (giữ nguyên cao độ), dùng khi bộ đọc
// (TTS engine) KHÔNG tự thực hiện tốc độ app yêu cầu.
//
// VÌ SAO CẦN: app chỉ "nhờ" engine đổi tốc độ qua TextToSpeech.setSpeechRate().
// Đó chỉ là một yêu cầu: engine Google làm đúng, nhưng nhiều engine khác bỏ qua
// hoặc chỉ làm một phần -> chỉnh tốc độ trong app không có tác dụng. Lớp này cho
// phép app đo thực tế rồi tự bù phần còn thiếu, nên tốc độ đúng với MỌI engine.
//
// Thuật toán: WSOLA (Waveform-Similarity Overlap-Add) - cắt tiếng thành các khung
// 30 ms chồng lấp 50%, đặt lại các khung với bước nhảy mới và tìm vị trí khớp
// dạng sóng nhất (tương quan chuẩn hoá) trong +-10 ms để các khung nối liền mà
// không đổi cao độ hay tạo tiếng "rè".
//
// Độ phức tạp: thời gian O(n * delta / 2) với delta ~ 10 ms (vài chục triệu phép
// nhân cho 1 phút tiếng nói), bộ nhớ O(n). Không phụ thuộc Android -> kiểm thử
// được bằng JUnit/main thông thường.
// ============================================================================
public final class SoftwareSpeed {

    private SoftwareSpeed() {
    }

    /** Dữ liệu PCM 16-bit đã giải mã (xen kẽ kênh). */
    public static final class Pcm {
        public final int sampleRate;
        public final int channels;
        public final short[] samples;

        Pcm(int sampleRate, int channels, short[] samples) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.samples = samples;
        }
    }

    private static final int MAX_WAV_BYTES = 192 * 1024 * 1024;

    // ------------------------------------------------------------------
    // Đọc / ghi WAV
    // ------------------------------------------------------------------

    /**
     * Đọc file WAV PCM 16-bit mono/stereo. Trả về null nếu định dạng khác (số thực,
     * 8/24-bit, nhiều hơn 2 kênh...) - khi đó không thể tự đổi tốc độ.
     */
    public static Pcm readPcm16(File file) throws IOException {
        long len = file.length();
        if (len < 44L) {
            return null;
        }
        if (len > MAX_WAV_BYTES) {
            throw new IOException("File âm thanh quá lớn để xử lý tốc độ.");
        }
        byte[] b = new byte[(int) len];
        try (InputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            if (off < b.length) {
                return null;
            }
        }
        return parsePcm16(b);
    }

    static Pcm parsePcm16(byte[] b) {
        if (b.length < 12 || !tagAt(b, 0, "RIFF") || !tagAt(b, 8, "WAVE")) {
            return null;
        }
        int pos = 12;
        int formatTag = -1;
        int channels = 0;
        int sampleRate = 0;
        int bits = 0;
        int dataOffset = -1;
        long dataSize = 0;
        while (pos + 8 <= b.length) {
            long size = le32(b, pos + 4) & 0xFFFFFFFFL;
            int body = pos + 8;
            if (tagAt(b, pos, "fmt ")) {
                if (size >= 16 && body + 16 <= b.length) {
                    formatTag = le16(b, body);
                    channels = le16(b, body + 2);
                    sampleRate = le32(b, body + 4);
                    bits = le16(b, body + 14);
                    // WAVE_FORMAT_EXTENSIBLE: mã thật nằm trong SubFormat.
                    if (formatTag == 0xFFFE && size >= 26 && body + 26 <= b.length) {
                        formatTag = le16(b, body + 24);
                    }
                }
            } else if (tagAt(b, pos, "data")) {
                dataOffset = body;
                long rest = b.length - (long) body;
                // Header "streaming" (size = 0 hoặc 0xFFFFFFFF): lấy phần còn lại của file.
                dataSize = (size == 0L || size == 0xFFFFFFFFL) ? rest : Math.min(size, rest);
                break;
            }
            long next = (long) body + size + (size & 1L);
            if (next > b.length) {
                break;
            }
            pos = (int) next;
        }
        if (formatTag != 1 || bits != 16 || channels < 1 || channels > 2 || sampleRate <= 0 || dataOffset < 0) {
            return null;
        }
        int frames = (int) (dataSize / (2L * channels));
        short[] s = new short[frames * channels];
        int p = dataOffset;
        for (int i = 0; i < s.length; i++, p += 2) {
            s[i] = (short) ((b[p] & 0xFF) | (b[p + 1] << 8));
        }
        return new Pcm(sampleRate, channels, s);
    }

    /** Ghi file WAV PCM 16-bit (header chuẩn 44 byte). */
    public static void writePcm16(File file, short[] samples, int channels, int sampleRate) throws IOException {
        long dataBytes = (long) samples.length * 2L;
        if (dataBytes + 36L > 0x7FFFFFFFL) {
            throw new IOException("Dữ liệu âm thanh quá lớn để ghi WAV.");
        }
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        put32(h, 4, (int) (36L + dataBytes));
        put(h, 8, "WAVE");
        put(h, 12, "fmt ");
        put32(h, 16, 16);
        put16(h, 20, 1);
        put16(h, 22, channels);
        put32(h, 24, sampleRate);
        put32(h, 28, sampleRate * channels * 2);
        put16(h, 32, channels * 2);
        put16(h, 34, 16);
        put(h, 36, "data");
        put32(h, 40, (int) dataBytes);
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16)) {
            out.write(h);
            byte[] buf = new byte[1 << 15];
            int filled = 0;
            for (int i = 0; i < samples.length; i++) {
                short v = samples[i];
                buf[filled++] = (byte) (v & 0xFF);
                buf[filled++] = (byte) ((v >> 8) & 0xFF);
                if (filled == buf.length) {
                    out.write(buf, 0, filled);
                    filled = 0;
                }
            }
            if (filled > 0) {
                out.write(buf, 0, filled);
            }
        }
    }

    /**
     * Thời lượng (giây) của phần CÓ TIẾNG trong file (bỏ khoảng lặng đầu/cuối). Dùng để
     * đo tốc độ thật của engine mà không bị sai lệch bởi độ trễ cố định đầu/cuối.
     * Trả về -1 nếu không phải PCM 16-bit mono/stereo, 0 nếu hoàn toàn im lặng.
     */
    public static double activeSeconds(File file) throws IOException {
        Pcm p = readPcm16(file);
        if (p == null) {
            return -1.0;
        }
        return activeSeconds(p.samples, p.channels, p.sampleRate);
    }

    static double activeSeconds(short[] s, int channels, int sampleRate) {
        int frames = s.length / channels;
        int peak = 0;
        for (int i = 0; i < s.length; i++) {
            int a = Math.abs((int) s[i]);
            if (a > peak) {
                peak = a;
            }
        }
        if (peak < 200) {
            return 0.0;
        }
        int thr = Math.max(60, (int) (peak * 0.03));
        int first = -1;
        int last = -1;
        for (int f = 0; f < frames; f++) {
            boolean loud = false;
            for (int c = 0; c < channels; c++) {
                if (Math.abs((int) s[f * channels + c]) > thr) {
                    loud = true;
                    break;
                }
            }
            if (loud) {
                if (first < 0) {
                    first = f;
                }
                last = f;
            }
        }
        if (first < 0) {
            return 0.0;
        }
        return (last - first + 1) / (double) sampleRate;
    }

    /**
     * Đổi tốc độ file WAV tại chỗ (speed > 1 nhanh hơn, < 1 chậm hơn). Trả về false và
     * KHÔNG đụng tới file nếu định dạng không hỗ trợ.
     */
    public static boolean stretchFileInPlace(File file, double speed) throws IOException {
        Pcm p = readPcm16(file);
        if (p == null) {
            return false;
        }
        short[] out = stretchPcm16(p.samples, p.channels, p.sampleRate, speed);
        File tmp = new File(file.getParentFile(), file.getName() + ".speed.tmp");
        try {
            writePcm16(tmp, out, p.channels, p.sampleRate);
            if (!tmp.renameTo(file)) {
                if (!file.delete() || !tmp.renameTo(file)) {
                    throw new IOException("Không thay được file âm thanh sau khi đổi tốc độ.");
                }
            }
        } finally {
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Thuật toán đổi tốc độ (WSOLA)
    // ------------------------------------------------------------------

    /**
     * Đổi tốc độ khối PCM 16-bit xen kẽ kênh. Kết quả có độ dài ~ khung/speed, cao độ giữ nguyên.
     */
    public static short[] stretchPcm16(short[] in, int channels, int sampleRate, double speed) {
        if (in == null || in.length == 0 || channels < 1 || sampleRate <= 0) {
            return in == null ? new short[0] : in.clone();
        }
        if (Double.isNaN(speed) || Double.isInfinite(speed)) {
            return in.clone();
        }
        speed = Math.max(0.25, Math.min(4.0, speed));
        if (Math.abs(speed - 1.0) < 0.005) {
            return in.clone();
        }
        final int frames = in.length / channels;
        if (frames == 0) {
            return new short[0];
        }
        final int outFrames = Math.max(1, (int) Math.round(frames / speed));

        int n = (int) Math.round(sampleRate * 0.030);
        n = Math.max(128, n + (n & 1)); // chẵn, tối thiểu 128
        final int hop = n / 2;
        if (frames < n * 3) {
            // Quá ngắn để ghép khung (< ~90 ms): nội suy tuyến tính là đủ.
            return resampleLinear(in, channels, frames, outFrames);
        }
        final int delta = Math.max(8, (int) Math.round(sampleRate * 0.010));

        // Tín hiệu đơn kênh dùng để so khớp, đệm số 0 ở cuối để khỏi kiểm tra biên.
        final float[] mono = new float[frames + 3 * n + 2 * delta + 8];
        for (int i = 0; i < frames; i++) {
            float acc = 0f;
            for (int c = 0; c < channels; c++) {
                acc += in[i * channels + c];
            }
            mono[i] = acc / channels;
        }

        final float[] win = new float[n];
        for (int i = 0; i < n; i++) {
            win[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / n)); // Hann tuần hoàn: tổng chồng 50% = 1
        }

        final int numFrames = (outFrames + hop - 1) / hop + 1;
        final float[] out = new float[((numFrames + 2) * hop + n) * channels];

        int prevChosen = 0;
        for (int k = 0; k < numFrames; k++) {
            final int t = k * hop;
            final int nominal = (int) Math.round((double) k * hop * speed);
            int chosen;
            if (k == 0) {
                chosen = 0;
            } else {
                final int nat = prevChosen + hop; // chỗ "tự nhiên" nối tiếp khung trước
                int lo = Math.max(-delta, -nominal);
                int hi = delta;
                if (nominal + hi > frames) {
                    hi = Math.max(lo, frames - nominal);
                }
                int bestD = Math.max(lo, Math.min(hi, 0));
                double bestScore = Double.NEGATIVE_INFINITY;
                for (int d = lo; d <= hi; d += 2) {
                    double sc = ncc(mono, nat, nominal + d, hop, 2) - 1e-4 * Math.abs(d) / delta;
                    if (sc > bestScore) {
                        bestScore = sc;
                        bestD = d;
                    }
                }
                for (int d = Math.max(lo, bestD - 1); d <= Math.min(hi, bestD + 1); d++) {
                    double sc = ncc(mono, nat, nominal + d, hop, 1) - 1e-4 * Math.abs(d) / delta;
                    if (sc > bestScore) {
                        bestScore = sc;
                        bestD = d;
                    }
                }
                chosen = nominal + bestD;
            }
            if (chosen < 0) {
                chosen = 0;
            }
            if (chosen > frames - 1) {
                chosen = frames - 1;
            }
            for (int i = 0; i < n; i++) {
                int src = chosen + i;
                if (src >= frames) {
                    break;
                }
                // Khung đầu: nửa đầu giữ nguyên (không mờ dần) để không làm yếu âm đầu câu.
                float w = (k == 0 && i < hop) ? 1f : win[i];
                int o = (t + i) * channels;
                int s = src * channels;
                for (int c = 0; c < channels; c++) {
                    out[o + c] += w * in[s + c];
                }
            }
            prevChosen = chosen;
        }

        short[] res = new short[outFrames * channels];
        for (int i = 0; i < res.length; i++) {
            long v = Math.round(out[i]);
            res[i] = (short) (v > 32767L ? 32767L : (v < -32768L ? -32768L : v));
        }
        return res;
    }

    // Tương quan chuẩn hoá giữa mono[a..a+len) và mono[b..b+len), lấy mẫu theo bước `stride`.
    private static double ncc(float[] mono, int a, int b, int len, int stride) {
        double dot = 0.0;
        double ea = 0.0;
        double eb = 0.0;
        for (int i = 0; i < len; i += stride) {
            float x = mono[a + i];
            float y = mono[b + i];
            dot += x * y;
            ea += x * x;
            eb += y * y;
        }
        return dot / Math.sqrt(ea * eb + 1e-9);
    }

    private static short[] resampleLinear(short[] in, int channels, int frames, int outFrames) {
        short[] res = new short[outFrames * channels];
        double step = outFrames <= 1 ? 0.0 : (frames - 1) / (double) (outFrames - 1);
        for (int j = 0; j < outFrames; j++) {
            double pos = j * step;
            int i0 = (int) pos;
            int i1 = Math.min(frames - 1, i0 + 1);
            double fr = pos - i0;
            for (int c = 0; c < channels; c++) {
                double a = in[i0 * channels + c];
                double b2 = in[i1 * channels + c];
                res[j * channels + c] = (short) Math.round(a + (b2 - a) * fr);
            }
        }
        return res;
    }

    // ------------------------------------------------------------------
    // Tiện ích byte
    // ------------------------------------------------------------------
    private static boolean tagAt(byte[] b, int off, String tag) {
        if (off + 4 > b.length) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (b[off + i] != (byte) tag.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static void put(byte[] b, int off, String tag) {
        for (int i = 0; i < 4; i++) {
            b[off + i] = (byte) tag.charAt(i);
        }
    }

    private static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void put32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }
}

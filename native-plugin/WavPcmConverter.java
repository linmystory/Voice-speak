package com.docdoc.app;

import java.io.IOException;
import java.io.OutputStream;

// ============================================================================
// WavPcmConverter.java
//
// Đổi 1 khối dữ liệu PCM 16-bit little-endian sang tần số lấy mẫu (Hz) và/hoặc
// số kênh (mono/stereo) khác.
//
// VÌ SAO CẦN: khi lưu file, TtsFileSaverPlugin ghép nhiều đoạn WAV nhỏ thành 1
// file. Header của file ghép chỉ ghi được MỘT tần số lấy mẫu (lấy từ đoạn đầu).
// Nếu có đoạn nào do engine xuất ở tần số khác (ví dụ 22050 Hz so với 24000 Hz),
// bản cũ chỉ ghi cảnh báo rồi vẫn nối thẳng dữ liệu -> đoạn đó khi phát lại sẽ
// bị NHANH hơn hoặc CHẬM hơn (kèm đổi cao độ) mà không hề báo lỗi. Lớp này đổi
// đoạn lệch về đúng định dạng của đoạn đầu (nội suy tuyến tính) để tốc độ luôn
// đúng.
//
// Độ phức tạp: thời gian O(n), bộ nhớ thêm O(1) (chỉ 1 bộ đệm ghi cố định).
// ============================================================================
public final class WavPcmConverter {

    // Số khung (frame) gom lại trước mỗi lần ghi ra OutputStream.
    private static final int FRAMES_PER_WRITE = 4096;

    private WavPcmConverter() {
    }

    /**
     * Số khung đầu ra sau khi đổi tần số lấy mẫu từ srcRate sang dstRate
     * (làm tròn xuống). Dùng để tính TRƯỚC kích thước dữ liệu ghi vào header.
     */
    public static long outputFrames(long srcFrames, int srcRate, int dstRate) {
        if (srcFrames <= 0L || srcRate <= 0 || dstRate <= 0) {
            return 0L;
        }
        if (srcRate == dstRate) {
            return srcFrames;
        }
        return (srcFrames * (long) dstRate) / (long) srcRate;
    }

    /**
     * Đổi srcFrames khung PCM 16-bit trong src sang (dstChannels, dstRate) và
     * ghi ra out. Số byte ghi ra luôn đúng bằng
     * outputFrames(srcFrames, srcRate, dstRate) * dstChannels * 2.
     */
    public static void convertPcm16(
            byte[] src,
            int srcFrames,
            int srcChannels,
            int srcRate,
            int dstChannels,
            int dstRate,
            OutputStream out) throws IOException {

        if (srcChannels < 1 || srcChannels > 2 || dstChannels < 1 || dstChannels > 2) {
            throw new IOException("Chỉ hỗ trợ đổi định dạng âm thanh mono/stereo 16-bit.");
        }
        if (srcRate <= 0 || dstRate <= 0) {
            throw new IOException("Tần số lấy mẫu không hợp lệ (" + srcRate + " -> " + dstRate + ").");
        }
        if (srcFrames <= 0) {
            return;
        }
        if ((long) srcFrames * srcChannels * 2L > (long) src.length) {
            throw new IOException("Dữ liệu âm thanh nguồn bị thiếu so với kích thước khai báo.");
        }

        final long outFrames = outputFrames(srcFrames, srcRate, dstRate);
        final int dstFrameBytes = dstChannels * 2;
        final byte[] buf = new byte[FRAMES_PER_WRITE * dstFrameBytes];
        final double step = (double) srcRate / (double) dstRate;
        int filled = 0;

        for (long j = 0; j < outFrames; j++) {
            final double pos = j * step;
            int i0 = (int) pos;
            if (i0 > srcFrames - 1) {
                i0 = srcFrames - 1;
            }
            final int i1 = (i0 + 1 < srcFrames) ? i0 + 1 : i0;
            final double frac = pos - i0;

            for (int c = 0; c < dstChannels; c++) {
                final double a = sampleMixed(src, i0, srcChannels, dstChannels, c);
                final double b = (i1 == i0) ? a : sampleMixed(src, i1, srcChannels, dstChannels, c);
                long v = Math.round(a + (b - a) * frac);
                if (v > 32767L) {
                    v = 32767L;
                } else if (v < -32768L) {
                    v = -32768L;
                }
                buf[filled++] = (byte) (v & 0xFF);
                buf[filled++] = (byte) ((v >> 8) & 0xFF);
            }

            if (filled == buf.length) {
                out.write(buf, 0, filled);
                filled = 0;
            }
        }
        if (filled > 0) {
            out.write(buf, 0, filled);
        }
    }

    // Lấy mẫu của kênh dstCh tại khung `frame`, đã quy đổi số kênh:
    //  - cùng số kênh: giữ nguyên
    //  - mono -> stereo: nhân đôi sang cả 2 kênh
    //  - stereo -> mono: trung bình cộng 2 kênh
    private static double sampleMixed(byte[] src, int frame, int srcCh, int dstCh, int c) {
        if (srcCh == dstCh) {
            return readS16(src, (frame * srcCh + c) * 2);
        }
        if (srcCh == 1) {
            return readS16(src, frame * 2);
        }
        final int base = frame * 4;
        return (readS16(src, base) + readS16(src, base + 2)) / 2.0;
    }

    private static int readS16(byte[] b, int off) {
        return (short) ((b[off] & 0xFF) | (b[off + 1] << 8));
    }
}

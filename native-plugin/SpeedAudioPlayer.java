package com.docdoc.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import java.io.IOException;

// ============================================================================
// SpeedAudioPlayer.java
//
// Phát PCM 16-bit đã được SoftwareSpeed đổi tốc độ, qua MỘT AudioTrack dùng chung
// cho cả phiên đọc nên các đoạn nối liền nhau (không đóng/mở lại audio track giữa
// các đoạn). Chỉ dùng khi bộ đọc không tự thực hiện được tốc độ yêu cầu; khi
// engine làm đúng, app vẫn để chính engine phát như trước.
//
// Luồng gọi: write()/finish()/release() gọi từ luồng đọc (executor); abortAsync()
// an toàn khi gọi từ luồng khác (nút Dừng) để âm thanh tắt ngay lập tức.
// ============================================================================
public final class SpeedAudioPlayer {

    /** Cho phép player kiểm tra người dùng đã bấm Dừng chưa. */
    public interface CancelFlag {
        boolean isCancelled();
    }

    private static final String TAG = "SpeedAudioPlayer";

    private final AudioAttributes attributes;
    private volatile AudioTrack track;
    private int trackRate = -1;
    private int trackChannels = -1;
    private int bufferFrames = 0;
    private long framesWritten = 0L;

    public SpeedAudioPlayer(AudioAttributes attributes) {
        this.attributes = attributes;
    }

    /**
     * Ghi (chặn tới khi đủ chỗ trong bộ đệm) khối PCM xen kẽ kênh. Ghi theo từng lát ~100 ms
     * để phản ứng nhanh khi người dùng bấm Dừng. Tự phát nốt dữ liệu cũ rồi tạo lại AudioTrack
     * nếu định dạng (tần số/số kênh) đổi giữa 2 đoạn.
     */
    public void write(short[] pcm, int sampleRate, int channels, CancelFlag cancel) throws IOException {
        if (pcm == null || pcm.length == 0) {
            return;
        }
        if (channels < 1 || channels > 2 || sampleRate <= 0) {
            throw new IOException("Định dạng âm thanh không hợp lệ (" + sampleRate + "Hz/" + channels + "ch).");
        }
        if (track != null && (trackRate != sampleRate || trackChannels != channels)) {
            finish(cancel); // phát hết đoạn cũ trước khi đổi định dạng
        }
        if (cancel.isCancelled()) {
            return;
        }
        if (track == null) {
            createTrack(sampleRate, channels);
        }
        final AudioTrack t = track;
        if (t == null) {
            return; // đã bị abort giữa chừng
        }
        final int slice = Math.max(channels, (sampleRate / 10) * channels);
        int off = 0;
        int zeroWrites = 0;
        try {
            while (off < pcm.length) {
                if (cancel.isCancelled()) {
                    return;
                }
                int n = Math.min(slice, pcm.length - off);
                int w = t.write(pcm, off, n);
                if (w < 0) {
                    if (cancel.isCancelled()) {
                        return; // track bị abort từ luồng khác -> lỗi này là bình thường
                    }
                    throw new IOException("AudioTrack.write lỗi (mã " + w + ").");
                }
                if (w == 0) {
                    // Hiếm: không nhận thêm dữ liệu. Tránh vòng lặp vô hạn.
                    if (++zeroWrites > 200) {
                        throw new IOException("AudioTrack không nhận thêm dữ liệu.");
                    }
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                zeroWrites = 0;
                off += w;
                framesWritten += w / channels;
            }
        } catch (IllegalStateException e) {
            if (cancel.isCancelled()) {
                return;
            }
            throw new IOException("AudioTrack ở trạng thái không hợp lệ: " + e.getMessage());
        }
    }

    /**
     * Báo hiệu hết dữ liệu: để AudioTrack phát nốt phần còn trong bộ đệm rồi giải phóng.
     * Chờ tối đa bằng thời lượng bộ đệm + 0,5 giây; thoát ngay nếu người dùng bấm Dừng.
     */
    public void finish(CancelFlag cancel) {
        final AudioTrack t = track;
        if (t == null) {
            return;
        }
        try {
            t.stop(); // MODE_STREAM: phát hết dữ liệu đã ghi rồi mới dừng
            long maxWaitMs = (bufferFrames * 1000L) / Math.max(1, trackRate) + 500L;
            long deadline = System.currentTimeMillis() + maxWaitMs;
            while (!cancel.isCancelled() && System.currentTimeMillis() < deadline) {
                long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                if (head >= framesWritten) {
                    break;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "finish(): " + e.getMessage());
        } finally {
            release();
        }
    }

    /** Tắt tiếng NGAY (an toàn khi gọi từ luồng khác): tạm dừng và xoá bộ đệm. Không giải phóng track. */
    public void abortAsync() {
        final AudioTrack t = track;
        if (t == null) {
            return;
        }
        try {
            t.pause();
            t.flush();
        } catch (Throwable e) {
            Log.w(TAG, "abortAsync(): " + e.getMessage());
        }
    }

    /** Giải phóng AudioTrack (gọi từ luồng đọc). Gọi nhiều lần vẫn an toàn. */
    public void release() {
        final AudioTrack t = track;
        track = null;
        framesWritten = 0L;
        trackRate = -1;
        trackChannels = -1;
        if (t == null) {
            return;
        }
        try {
            t.release();
        } catch (Throwable e) {
            Log.w(TAG, "release(): " + e.getMessage());
        }
    }

    private void createTrack(int sampleRate, int channels) throws IOException {
        final int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        final int minBytes = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) {
            throw new IOException("Thiết bị không hỗ trợ phát " + sampleRate + "Hz/" + channels + " kênh.");
        }
        // Bộ đệm ~0,5 giây (không nhỏ hơn 2x mức tối thiểu): đủ để không bị đứt tiếng giữa các
        // đoạn, nhưng ngắn để tiến độ hiển thị không đi trước tiếng đang phát quá xa.
        final int bytes = Math.max(minBytes * 2, sampleRate * channels * 2 / 2);
        final AudioTrack t;
        try {
            t = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(mask)
                            .build())
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (RuntimeException e) {
            throw new IOException("Không tạo được AudioTrack: " + e.getMessage());
        }
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            try {
                t.release();
            } catch (Throwable ignored) {
            }
            throw new IOException("AudioTrack không khởi tạo được.");
        }
        try {
            t.play();
        } catch (IllegalStateException e) {
            try {
                t.release();
            } catch (Throwable ignored) {
            }
            throw new IOException("Không bắt đầu phát được: " + e.getMessage());
        }
        track = t;
        trackRate = sampleRate;
        trackChannels = channels;
        bufferFrames = bytes / (2 * channels);
        framesWritten = 0L;
    }
}

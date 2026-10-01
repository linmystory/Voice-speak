package com.docdoc.app;

import android.os.Bundle;
import android.util.Log;
import com.getcapacitor.BridgeActivity;

// ============================================================================
// File này THAY THẾ cho file MainActivity.java mặc định mà Capacitor tự tạo
// ra tại: android/app/src/main/java/com/docdoc/app/MainActivity.java
//
// Điểm khác biệt so với bản mặc định:
//   1) registerPlugin(TtsFileSaverPlugin.class) để Capacitor biết nạp plugin
//      TtsFileSaverPlugin mà ta viết tay.
//   2) Bọc registerPlugin() trong try/catch: nếu vì lý do nào đó việc đăng ký
//      plugin thất bại (ví dụ lỗi build/thiếu class do quên copy file), ứng
//      dụng vẫn mở lên bình thường và chạy được ở chế độ Web Speech API
//      (JS trong www/index.html đã tự kiểm tra window.Capacitor.Plugins và
//      chuyển sang thông báo phù hợp) thay vì bị crash ngay khi khởi động.
// ============================================================================
public class MainActivity extends BridgeActivity {

    private static final String TAG = "MainActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        try {
            registerPlugin(TtsFileSaverPlugin.class);
        } catch (Throwable t) {
            Log.e(TAG, "Không thể đăng ký TtsFileSaverPlugin, ứng dụng sẽ chạy" +
                    " ở chế độ giới hạn (chỉ đọc bằng Web Speech API): " + t.getMessage(), t);
        }
        super.onCreate(savedInstanceState);
    }
}

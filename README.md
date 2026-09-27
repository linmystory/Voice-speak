# Đọc Văn Bản — Android TTS

Bản GitHub-ready để GitHub Actions tự tạo Android project, build APK debug và xuất APK thành artifact.

## Tạo APK bằng GitHub

1. Tạo repository GitHub mới.
2. Upload **toàn bộ nội dung bên trong thư mục `tts-app-can-thiet`** vào repository (không tạo thêm một thư mục lồng bên ngoài).
3. Commit lên nhánh `main`.
4. Mở tab **Actions** → workflow **Build Android APK** → **Run workflow**.
5. Chờ job `build-apk` hoàn thành.
6. Mở phần **Artifacts** của workflow run và tải `doc-van-ban-tts-debug-apk`.
7. Giải nén artifact để lấy `app-debug.apk`, sau đó cài lên Android.

Workflow dùng Node.js 20 và JDK 17, tự chạy `npx cap add android`, chép native plugin và build bằng Gradle.

## Chạy cục bộ

Yêu cầu Node.js 20+, JDK 17+ và Android SDK/Android Studio.

```bash
npm install
npm run android:init
npm run android:sync
npm run build:android
```

APK debug:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Tính năng

- Đọc văn bản bằng Android Text-to-Speech khi Web Speech API không khả dụng.
- Dừng đọc an toàn, không khóa giao diện khi TTS kết thúc hoặc bị lỗi.
- Liệt kê/chọn voice native.
- Tổng hợp WAV theo từng đoạn và ghép theo RIFF `fmt ` / `data` chunk thực tế.
- Kiểm tra tính tương thích của các đoạn PCM trước khi ghép.
- Lưu WAV vào `Music/DocDocTTS` bằng MediaStore trên Android 10+.
- Chia sẻ WAV bằng Android Sharesheet.
- Dọn file tạm và xử lý lỗi ghi MediaStore.

## Lưu ý tương thích

- Chức năng đọc native phụ thuộc Android Text-to-Speech engine và voice có sẵn trên thiết bị.
- Xuất WAV công khai yêu cầu Android 10 (API 29)+ trong bản này.
- Ứng dụng không dùng database/API bên ngoài; nội dung văn bản được xử lý cục bộ.

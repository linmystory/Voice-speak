# Hướng dẫn cài đặt & build ứng dụng "Đọc Văn Bản" (TTS)

> **Cập nhật v2.4 — sửa xung đột TalkBack, tăng tốc lưu file, sửa lỗi không dừng khi xoá văn bản:**
> - **Xung đột với TalkBack (hoặc bất kỳ app nào dùng chung 1 bộ đọc):** khi 2 ứng
>   dụng cùng dùng chung một engine TTS, TalkBack giành quyền nói bằng cách xoá
>   sạch hàng đợi của engine đó (`QUEUE_DESTROY`) — Android báo việc này qua
>   `onStop()`, một callback mà bản cũ **không hề xử lý**, nên cứ đứng im chờ hết
>   giờ (30 giây khi đọc, còn khi lưu file thì báo lỗi ngay) thay vì đọc/lưu tiếp.
>   Dùng bộ đọc RIÊNG (khác bộ đọc TalkBack đang dùng) thì không đụng hàng đợi
>   nên vẫn bình thường — đúng như hiện tượng đã mô tả. Đã vá: coi `onStop()`
>   không phải do chính mình gây ra là "bị chen ngang", tự động xếp lại đúng
>   câu/đoạn đó sau một khoảng nghỉ ngắn, cả khi đọc qua loa lẫn khi xuất file.
> - **Lưu âm thanh quá lâu:** 2 nguyên nhân được tìm thấy và sửa — (1) nếu đang
>   đọc dở qua loa mà bấm Lưu, việc lưu file phải xếp hàng chờ đọc xong toàn bộ
>   văn bản mới bắt đầu (nay tự động dừng đọc trước); (2) mỗi đoạn văn bản trước
>   đây phải xong hẳn rồi mới gửi đoạn kế cho engine (nay xếp sẵn nhiều đoạn theo
>   kiểu "băng chuyền", engine luôn có việc để làm ngay), và bỏ hẳn bước ghép file
>   trung gian (ghi thẳng vào bộ nhớ máy). Có thêm thanh tiến độ "Đang tạo file...
>   x/y đoạn" để biết app vẫn đang chạy, không phải bị treo.
> - **Xoá văn bản trong lúc đang đọc nhưng app vẫn tiếp tục đọc:** nay hễ ô nhập
>   văn bản trở nên rỗng (xoá tay hoặc bấm "Xoá nhanh") trong lúc đang đọc, app
>   tự động dừng ngay lập tức.
> - Thêm `aria-label`/`aria-live` cho các nút và vùng trạng thái để tương thích
>   tốt hơn khi chính người dùng TalkBack điều khiển app này.

> **Cập nhật v2.3 — sửa lỗi âm thanh + giao diện gọn hơn:**
> - **Sửa mất tiếng đầu câu / ngắt quãng giữa chừng khi đọc dài:** nguyên nhân do đọc từng câu qua nhiều lệnh gọi riêng lẻ (round-trip JS↔Kotlin) khiến audio route bị đóng/mở lại liên tục. Đã gộp lại thành **1 lệnh đọc liên tục duy nhất** cho toàn bộ đoạn văn bản, chỉ báo tiến độ qua sự kiện (không cắt luồng phát).
> - Xin `AudioFocus` tường minh và cấu hình `AudioAttributes` đồng bộ giữa việc "giữ loa" và engine TTS thật sự phát ra — tránh xung đột thiết bị đầu ra.
> - Thêm `WakeLock` (giữ CPU thức) trong lúc đọc/xuất file dài — tránh Android đưa app vào chế độ tiết kiệm pin (Doze) làm treo audio khi khóa màn hình giữa chừng. **Yêu cầu quyền `WAKE_LOCK`** (quyền thường, không cần xin lúc chạy) — xem bước vá `AndroidManifest.xml` ở cả 2 cách build bên dưới.
> - **Giao diện thiết kế lại:** gộp nút Phát và Dừng thành 1 nút duy nhất; thêm menu **⚙️ Cài đặt** (góc trên-trái) gom 4 mục Danh sách bộ đọc / Danh sách giọng đọc / Tốc độ đọc / Cao độ giọng nói vào một chỗ, ẩn cho tới khi bấm; nút **💾 Lưu âm thanh** chuyển sang góc trên-phải; đã **bỏ hẳn** 2 nút Tua lùi/Tua tới để màn hình gọn hơn.

> **Cập nhật v2.2 — nâng cấp lên Capacitor 8 (công nghệ mới nhất hiện tại):**
> - Nâng `@capacitor/core`, `@capacitor/android`, `@capacitor/cli` từ `6.1.2` (đã cũ 2 major version) lên **`8.5.2`** (đồng bộ cả 3 gói, đúng khuyến nghị chính thức của Capacitor) — bản ổn định mới nhất tại thời điểm cập nhật.
> - **Lý do bắt buộc:** từ 31/8/2026, Google Play yêu cầu mọi app mới/bản cập nhật phải target Android 16 (API 36). Capacitor 6 mặc định chỉ target API 34 → sẽ bị Google Play từ chối. Capacitor 8 mặc định target đúng API 36, không cần vá thêm.
> - Nâng Kotlin `1.9.24` → `2.2.20`, JDK `17` → `21`, Node.js `20` → `22` trong CI, đúng theo yêu cầu chính thức của Capacitor 8.
> - **Đã build thành công thực tế qua GitHub Actions** (dấu tích xanh, artifact `app-debug-apk` được tạo) — lo ngại trước đây về việc AGP 8.13.0 chưa được hỗ trợ chính thức (ionic-team/capacitor#8292) **không chặn build dòng lệnh** `./gradlew assembleDebug` trong CI như đã lo ngại ban đầu; cảnh báo đó (nếu còn) chỉ xuất hiện khi mở project bằng Android Studio GUI.

> **Cập nhật v2.1:**
> - Bổ sung `getEnginesWithVoices()` trong plugin native: lấy **toàn bộ bộ đọc (TTS engine)** cài trên máy (Google TTS, Samsung TTS, v.v.) kèm **toàn bộ giọng đọc trong từng bộ đọc** — trước đây chỉ lấy giọng của engine mặc định. Dropdown "Bộ đọc" ở chế độ native giờ liệt kê đầy đủ như chế độ Web Speech API.
> - Bổ sung `setEngine()`: khi người dùng đổi bộ đọc trong dropdown, app chuyển đúng engine Android thật sự dùng để đọc/lưu file, không chỉ đổi giao diện.

> **Lưu ý phạm vi:** bản này CHỈ HỖ TRỢ ANDROID 10 (API 29) TRỞ LÊN.
> Toàn bộ code xử lý quyền lưu trữ runtime kiểu cũ (`WRITE_EXTERNAL_STORAGE`)
> và khai báo `FileProvider` cho Android 9 trở xuống đã được **loại bỏ** để
> gọn nhẹ hơn. Tính năng "Lưu âm thanh (file WAV)" dùng MediaStore/scoped
> storage của Android 10+, không cần xin quyền lúc chạy.

Ứng dụng chạy trên nền [Capacitor](https://capacitorjs.com/) (WebView + plugin
native Kotlin). Có 2 cách để có file APK: **build tự động qua GitHub Actions**
(khuyên dùng, không cần cài Android Studio) hoặc **build thủ công trên máy**.

---

## Cách 1 — Build tự động bằng GitHub Actions (khuyên dùng)

1. Tạo một repository GitHub mới (public hoặc private đều được), rồi đẩy toàn
   bộ thư mục này lên:
   ```bash
   git init
   git add .
   git commit -m "Init TTS app"
   git branch -M main
   git remote add origin <URL_repo_cua_ban>
   git push -u origin main
   ```
2. Vào tab **Actions** trên GitHub, workflow "Build Android APK" sẽ tự chạy
   ngay sau khi push (hoặc bấm **Run workflow** để chạy thủ công).
3. Chờ vài phút cho tới khi job "build" chạy xong (dấu tích xanh).
4. Mở job đó ra, kéo xuống mục **Artifacts**, tải file `app-debug-apk` về máy
   (đây là file `.apk` nén trong `.zip`, giải nén ra là cài được).
5. Copy file `.apk` vào điện thoại Android 10 trở lên, mở lên để cài (cần bật
   "Cho phép cài từ nguồn không xác định" trong Cài đặt nếu máy hỏi).

Workflow đã tự động thực hiện toàn bộ các bước sau, bạn **không cần làm gì
thêm**:
- Tạo project Android từ Capacitor (`npx cap add android`)
- Đặt `minSdkVersion = 29` trong `android/variables.gradle` để giới hạn
  ứng dụng chỉ cài được trên Android 10 trở lên
- Vá `AndroidManifest.xml`: thêm `<queries>` (để thấy hết mọi bộ đọc TTS đã
  cài trên máy, bắt buộc từ Android 11+) và quyền `WAKE_LOCK` (để không bị
  ngắt âm thanh khi khóa màn hình giữa lúc đọc dài)
- Bật hỗ trợ biên dịch Kotlin
- Copy plugin `TtsFileSaverPlugin.kt` và `MainActivity.java` vào đúng vị trí
- Build file APK bản debug (luôn chạy) + APK/AAB bản release đã ký (chỉ chạy
  nếu đã cấu hình đủ 4 secret ở mục ngay dưới đây — nếu chưa, workflow tự bỏ
  qua các bước này, không làm hỏng bản debug)

### (Tùy chọn) Bật build bản release đã ký — để đăng lên Google Play

Bản debug ở trên đủ để cài thử trên điện thoại, nhưng Google Play **bắt buộc**
phải là bản đã ký bằng khóa riêng (keystore) của bạn và ở định dạng `.aab`
(Android App Bundle). Làm 1 lần duy nhất:

**Bước 1 — Tạo keystore** (bỏ qua nếu đã có sẵn 1 file `.jks`/`.keystore` từ
trước — dùng lại đúng file đó, **không tạo mới**, vì mỗi lần cập nhật app lên
Play sau này đều phải ký bằng đúng 1 keystore duy nhất, làm mất là không thể
cập nhật app cũ được nữa):
```bash
keytool -genkeypair -v -keystore release-keystore.jks -alias doc-van-ban \
  -keyalg RSA -keysize 2048 -validity 10000
```
Lệnh sẽ hỏi vài thông tin (tên, tổ chức...) rồi hỏi 2 mật khẩu — **ghi nhớ
thật kỹ, mất là không khôi phục được**.

**Bước 2 — Mã hóa keystore thành base64** (để dán vào GitHub Secret dạng chữ):
```bash
base64 -w0 release-keystore.jks > keystore.b64.txt
```
Mở file `keystore.b64.txt`, copy toàn bộ nội dung (1 chuỗi ký tự rất dài).

**Bước 3 — Thêm 4 Secret vào GitHub:** vào repo → **Settings** → **Secrets
and variables** → **Actions** → **New repository secret**, tạo lần lượt:

| Tên secret          | Giá trị                                              |
|---------------------|-------------------------------------------------------|
| `KEYSTORE_BASE64`   | Toàn bộ nội dung file `keystore.b64.txt` ở Bước 2      |
| `KEYSTORE_PASSWORD` | Mật khẩu keystore đã đặt ở Bước 1                      |
| `KEY_ALIAS`         | `doc-van-ban` (hoặc alias bạn đã đặt ở Bước 1)         |
| `KEY_PASSWORD`      | Mật khẩu key đã đặt ở Bước 1                           |

Chạy lại workflow (push code mới hoặc bấm **Run workflow**) — job sẽ xuất
hiện thêm artifact **`app-release-signed`** chứa cả `.apk` và `.aab` đã ký
sẵn, tải `.aab` lên Google Play Console để đăng app.

⚠️ **Không bao giờ** commit file `.jks`/`.keystore` hay dán mật khẩu thẳng
vào code — chỉ đưa qua GitHub Secrets như trên. Log của Actions tự động che
(`***`) mọi giá trị secret nên không lộ ra dù build lỗi.

## Cách 2 — Build thủ công trên máy tính (cần Android Studio / Android SDK)

Yêu cầu: Node.js 18+, JDK 17, Android SDK (thông qua Android Studio).

```bash
npm install
npx cap add android

# Giới hạn ứng dụng chỉ hỗ trợ Android 10 (API 29) trở lên — sửa dòng
# minSdkVersion trong android/variables.gradle thành 29 (bằng tay hoặc chạy):
sed -i 's/minSdkVersion = [0-9]\+/minSdkVersion = 29/' android/variables.gradle

# QUAN TRỌNG — đừng bỏ qua bước này: vá AndroidManifest.xml để (1) thấy được
# TẤT CẢ bộ đọc TTS đã cài trên máy chứ không riêng bộ đọc mặc định (bắt buộc
# từ Android 11+ do cơ chế package visibility), và (2) xin quyền WAKE_LOCK để
# không bị ngắt âm thanh khi khóa màn hình giữa lúc đang đọc văn bản dài.
# Thiếu bước này, app vẫn build và chạy được, nhưng sẽ dính lại 2 lỗi đã biết
# ở trên (không thấy hết bộ đọc / dễ ngắt quãng khi khóa máy).
sed -i '/<manifest /a\    <uses-permission android:name="android.permission.WAKE_LOCK" />\n    <queries>\n        <intent>\n            <action android:name="android.intent.action.TTS_SERVICE" />\n        </intent>\n    </queries>' android/app/src/main/AndroidManifest.xml

# Bật hỗ trợ Kotlin cho project Android (chỉ cần làm 1 lần)
# — xem chi tiết 3 dòng sed trong .github/workflows/build.yml, bước
#   "Bật hỗ trợ biên dịch Kotlin cho project Android" —

# Copy plugin native vào project
mkdir -p android/app/src/main/java/com/docdoc/app
cp native-plugin/TtsFileSaverPlugin.kt android/app/src/main/java/com/docdoc/app/
cp native-plugin/MainActivity.java android/app/src/main/java/com/docdoc/app/

npx cap sync android
cd android
./gradlew assembleDebug
```

File APK sẽ nằm ở `android/app/build/outputs/apk/debug/app-debug.apk`.

---

## Ghi chú quan trọng

- Ứng dụng hoạt động **hoàn toàn ngoại tuyến**, dùng giọng đọc có sẵn trên máy
  (Google TTS / Samsung TTS / v.v. tùy máy). Nếu máy chưa cài giọng tiếng
  Việt, vào **Cài đặt > Ngôn ngữ & nhập > Chuyển văn bản thành giọng nói** để
  tải thêm giọng.
- Tính năng **"Lưu âm thanh (file WAV)"** chỉ hoạt động trong bản APK đã cài
  đặt, **không hoạt động** khi mở `www/index.html` trực tiếp bằng trình duyệt
  máy tính/điện thoại — vì nó cần plugin native Kotlin chỉ tồn tại bên trong
  ứng dụng đã đóng gói.
- Ứng dụng yêu cầu **Android 10 (API 29) trở lên**. Vì `minSdkVersion` đã đặt
  là 29, máy chạy Android 9 trở xuống sẽ **không cài được** ứng dụng (thay vì
  cài được rồi lỗi lúc dùng), tránh gây nhầm lẫn cho người dùng.

---

## Khắc phục sự cố (Troubleshooting)

**App mở lên nhưng báo "Không có bộ đọc khả dụng" / "Không tìm thấy giọng đọc":**
Máy chưa cài engine Text-to-Speech nào (thường gặp trên máy Trung Quốc,
ROM tuỳ biến đã gỡ Google TTS). Vào **Cài đặt > Ngôn ngữ & nhập liệu >
Chuyển văn bản thành giọng nói (Text-to-speech)**, chọn một engine (Google
TTS hoặc Samsung TTS) và tải gói giọng tiếng Việt. Đây không phải lỗi của
ứng dụng — plugin đã được vá để phát hiện và báo đúng tình huống này thay vì
treo vô thời hạn.

**Build GitHub Actions báo lỗi ở bước `sed` (không tìm thấy `minSdkVersion`
hoặc `dependencies {`):**
Điều này xảy ra nếu một phiên bản Capacitor mới hơn đổi cấu trúc file
`android/variables.gradle` hoặc `android/build.gradle`. Cách xử lý:
1. Mở log của bước bị lỗi trong tab Actions để xem `sed` không khớp được
   dòng nào.
2. Mở file tương ứng (`android/variables.gradle` hoặc `android/build.gradle`)
   sau bước `npx cap add android`, sửa tay dòng `minSdkVersion` thành `29`
   hoặc thêm thủ công dòng `classpath` Kotlin vào đúng khối `dependencies {}`
   trong `buildscript`.
3. Có thể ghim lại phiên bản Capacitor cũ hơn (đã test hoạt động tốt) trong
   `package.json` nếu không muốn sửa lại script `sed`.

**Build lỗi vì xung đột phiên bản Kotlin/AGP:**
Thử nâng số phiên bản `kotlin-gradle-plugin` và `kotlin-stdlib` trong
`.github/workflows/build.yml` lên bản mới hơn tương thích với phiên bản
Android Gradle Plugin (AGP) mà `npx cap add android` tạo ra, hoặc hạ phiên
bản `@capacitor/android` trong `package.json` xuống bản đã biết chạy ổn.

**Bấm "Lưu âm thanh" báo "hết bộ nhớ" với văn bản rất dài:**
Giảm bớt độ dài văn bản (ứng dụng đã giới hạn 25.000 ký tự ở khung nhập),
hoặc chia văn bản thành nhiều lần lưu file nhỏ hơn. Bản vá đã bắt riêng lỗi
`OutOfMemoryError` để báo rõ nguyên nhân thay vì làm ứng dụng đóng đột ngột.

**App đọc giọng bị sai ngôn ngữ / đọc bằng giọng tiếng Anh dù đã nhập tiếng
Việt:** Máy chưa cài gói giọng tiếng Việt cho engine TTS đang dùng. Plugin
đã được vá để tự động rơi về tiếng Anh (thay vì lỗi hẳn) khi ngôn ngữ yêu
cầu không có sẵn — hãy cài thêm giọng tiếng Việt theo hướng dẫn ở mục
"Không có bộ đọc khả dụng" phía trên để giọng đọc đúng như mong muốn.

**Âm thanh vẫn mất tiếng đầu câu hoặc ngắt quãng dù đã cập nhật v2.3:**
Từ v2.3, app đã xin `AudioFocus` tường minh, giữ `WakeLock`, và đọc liên tục
trong 1 lệnh duy nhất — khắc phục nguyên nhân phổ biến nhất. Nếu vẫn còn gặp
trên một máy/engine cụ thể:
1. Kiểm tra xem có đúng đã build lại APK **sau** khi cập nhật lên v2.3 hay
   đang dùng bản APK cũ (bản vá chỉ có hiệu lực từ lần build mới).
2. Thử đổi sang bộ đọc khác trong menu ⚙️ Cài đặt — một số engine bên thứ 3
   (không phải Google TTS) có thể cần thời gian khởi động lâu hơn 300ms mà
   plugin đang chờ; nếu xác định đúng là do engine cụ thể nào, có thể tăng
   thời lượng "làm nóng" trong `TtsFileSaverPlugin.kt` (tìm `playSilentUtterance`).
3. Kiểm tra máy có đang bật chế độ tiết kiệm pin quá mạnh (một số ROM tuỳ
   biến Trung Quốc có lớp quản lý pin riêng, mạnh hơn cả Doze gốc của
   Android) — có thể cần thêm ứng dụng vào danh sách "không tối ưu hoá pin"
   trong Cài đặt hệ thống.

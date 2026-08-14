# Kotlin Auto Refactor (K2)

Kotlin Auto Refactor is an IntelliJ IDEA and Android Studio plugin that safely renames Kotlin declarations and Android resources across a project. It scans the project first, shows the planned changes and conflicts, and then applies the selected refactors using Kotlin PSI and the K2 Analysis API.

> [Tiếng Việt](#tiếng-việt)

## English

### Features

- Rename top-level Kotlin classes, interfaces, objects, enum classes, annotations, and type aliases.
- Optionally rename functions and variables. Parameters, nested declarations, and enum entries are never targets.
- Skip generated or read-only declarations, SDK/library overrides, and unsafe accessor collisions.
- Rename `drawable*` and `layout*` resources together with qualifier variants such as `layout-land` and `drawable-night`.
- Rename strings, colors, and styles across `values*` variants.
- Update Kotlin, Java, XML, and View Binding references without editing generated files.
- Optionally shuffle functions and properties while preserving dependencies and initialization order.
- Preview changes and conflicts before modifying the project.
- Select one or more modules; **All modules** is equivalent to selecting every module.

Classes, type aliases, drawables, layouts, strings, colors, and styles are selected by default. Functions, variables, and shuffling are opt-in.

### Requirements

- IntelliJ IDEA 2025.1+ or a compatible Android Studio version
- Kotlin plugin with K2 support
- A Kotlin/Android project

The plugin supports IntelliJ Platform builds `251` through `261.*`.

### Install from a GitHub release

1. Open the [Releases page](https://github.com/chi2911ks/AutoRefactorPluginK2/releases).
2. Open the latest release and download the plugin `.zip` file from **Assets**. Do not extract it.
3. In Android Studio or IntelliJ IDEA, open **Settings/Preferences > Plugins**.
4. Click the gear icon, choose **Install Plugin from Disk...**, and select the downloaded ZIP.
5. Restart the IDE when prompted.

If the IDE reports that the plugin is incompatible, check the IDE build under **Help > About** and use a release that supports that build.

### Usage

1. Open a Kotlin or Android project and wait for indexing to finish.
2. Commit or back up your current changes before running a project-wide refactor.
3. Select **Tools > Kotlin Project Refactor (K2)**.
4. Select **All modules** or the modules you want to process.
5. Enter the suffix to add and, optionally, the text to remove.
6. Select the refactor operations. Functions, variables, and declaration shuffling must be enabled explicitly.
7. Click **Scan Project**.
8. Review the preview, resource selections, and **Conflicts** tab. Disabled or conflicting items will be skipped.
9. Click **OK** to apply the plan.
10. Review the generated `refactor-report-<timestamp>.md` file in the project root. Symbol diagnostics may also be written to `.autorefactor-symbols.log`.

The removal text is matched case-insensitively before the new suffix is added. Android resource names are normalized to lowercase underscore-separated segments, while style casing and dot-separated hierarchy are preserved.

Examples:

| Target | Before | After |
|---|---|---|
| Kotlin class | `CoreRecyclerINV069Adapter` | `CoreRecyclerAdapterINV125` |
| Layout/drawable | `inv069_bg_12_top` | `bg_12_top_inv125` |
| String/color | `inv069_tv_content` | `inv125_tv_content` |
| Style | `inv069_AppTheme.AdAttribution` | `inv125_AppTheme.AdAttribution` |

### Build from source

JDK 21 and the included Gradle wrapper are required.

```powershell
# Windows
.\gradlew.bat buildPlugin
.\gradlew.bat test
.\gradlew.bat check
.\gradlew.bat runIde
```

```bash
# macOS/Linux
./gradlew buildPlugin
./gradlew test
./gradlew check
./gradlew runIde
```

The installable ZIP is created in `build/distributions/`. The `runIde` task starts a sandbox IDE for manual testing.

---

## Tiếng Việt

Kotlin Auto Refactor là plugin dành cho IntelliJ IDEA và Android Studio, giúp đổi tên đồng bộ các khai báo Kotlin và tài nguyên Android trong toàn dự án. Plugin quét dự án, hiển thị trước kế hoạch cùng các xung đột, sau đó mới áp dụng những thay đổi đã chọn bằng Kotlin PSI và K2 Analysis API.

### Tính năng

- Đổi tên class, interface, object, enum class, annotation và typealias Kotlin ở cấp cao nhất.
- Có thể bật thêm đổi tên hàm và biến. Plugin không đổi tên độc lập parameter, khai báo lồng nhau hoặc enum entry.
- Bỏ qua mã được sinh tự động, file chỉ đọc, override từ SDK/thư viện và các accessor có nguy cơ xung đột.
- Đổi tên tài nguyên `drawable*`, `layout*` cùng toàn bộ biến thể qualifier như `layout-land`, `drawable-night`.
- Đổi tên string, color và style trong các biến thể `values*`.
- Cập nhật tham chiếu trong Kotlin, Java, XML và View Binding mà không sửa file sinh tự động.
- Có thể xáo trộn thứ tự hàm và property nhưng vẫn giữ dependency và thứ tự khởi tạo cần thiết.
- Xem trước thay đổi và xung đột trước khi sửa dự án.
- Chọn một hoặc nhiều module; **All modules** tương đương chọn tất cả module.

Mặc định plugin chọn class, typealias, drawable, layout, string, color và style. Đổi tên hàm, biến và xáo trộn khai báo là các tùy chọn cần bật thủ công.

### Yêu cầu

- IntelliJ IDEA 2025.1+ hoặc phiên bản Android Studio tương thích
- Kotlin plugin hỗ trợ K2
- Dự án Kotlin/Android

Plugin hỗ trợ IntelliJ Platform build từ `251` đến `261.*`.

### Cài đặt từ GitHub Release

1. Mở trang [Releases](https://github.com/chi2911ks/AutoRefactorPluginK2/releases).
2. Mở bản phát hành mới nhất và tải file plugin `.zip` trong mục **Assets**. Không giải nén file.
3. Trong Android Studio hoặc IntelliJ IDEA, mở **Settings/Preferences > Plugins**.
4. Nhấn biểu tượng bánh răng, chọn **Install Plugin from Disk...**, rồi chọn file ZIP vừa tải.
5. Khởi động lại IDE khi được yêu cầu.

Nếu IDE báo plugin không tương thích, kiểm tra build của IDE tại **Help > About** và chọn bản phát hành hỗ trợ build đó.

### Cách sử dụng

1. Mở dự án Kotlin hoặc Android và chờ IDE index xong.
2. Nên commit hoặc sao lưu các thay đổi hiện tại trước khi refactor toàn dự án.
3. Chọn **Tools > Kotlin Project Refactor (K2)**.
4. Chọn **All modules** hoặc các module cần xử lý.
5. Nhập hậu tố muốn thêm và, nếu cần, phần ký tự muốn xóa.
6. Chọn các thao tác refactor. Muốn đổi tên hàm, biến hoặc xáo trộn khai báo thì phải bật riêng từng tùy chọn.
7. Nhấn **Scan Project**.
8. Kiểm tra bảng xem trước, lựa chọn tài nguyên và tab **Conflicts**. Các mục bị vô hiệu hóa hoặc xung đột sẽ được bỏ qua.
9. Nhấn **OK** để áp dụng kế hoạch.
10. Kiểm tra file báo cáo `refactor-report-<timestamp>.md` trong thư mục gốc của dự án. Log chẩn đoán symbol có thể được ghi vào `.autorefactor-symbols.log`.

Phần ký tự cần xóa được so khớp không phân biệt chữ hoa/chữ thường trước khi thêm hậu tố mới. Tên tài nguyên Android được chuẩn hóa thành chữ thường và phân tách bằng dấu gạch dưới; chữ hoa/thường và cấu trúc phân cấp bằng dấu chấm của style vẫn được giữ nguyên.

Ví dụ:

| Đối tượng | Trước | Sau |
|---|---|---|
| Kotlin class | `CoreRecyclerINV069Adapter` | `CoreRecyclerAdapterINV125` |
| Layout/drawable | `inv069_bg_12_top` | `bg_12_top_inv125` |
| String/color | `inv069_tv_content` | `inv125_tv_content` |
| Style | `inv069_AppTheme.AdAttribution` | `inv125_AppTheme.AdAttribution` |

### Build từ mã nguồn

Cần JDK 21 và sử dụng Gradle wrapper có sẵn trong repository.

```powershell
# Windows
.\gradlew.bat buildPlugin
.\gradlew.bat test
.\gradlew.bat check
.\gradlew.bat runIde
```

```bash
# macOS/Linux
./gradlew buildPlugin
./gradlew test
./gradlew check
./gradlew runIde
```

File ZIP để cài đặt được tạo trong `build/distributions/`. Task `runIde` mở một IDE sandbox để kiểm thử thủ công.

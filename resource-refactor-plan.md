# Plan: Refactor Drawable and Layout Resources

## Mục tiêu

Thêm hai checkbox độc lập `Refactor drawables` và `Refactor layouts`. Cả hai mặc định bật và dùng module selection, suffix thêm và suffix cần loại bỏ hiện có.

Ví dụ resource:

- `ic_search` -> `ic_search_inv124`
- `activity_home` -> `activity_home_inv124`
- `activity_home_dn12` -> `activity_home_inv124` khi suffix loại bỏ là `dn12`

## Phạm vi và model

- Bổ sung flags vào `RefactorOptions` và model `ResourceRename` gồm loại resource, tên cũ/mới, các file variant, usages và trạng thái chọn.
- Scanner chỉ thu thập resource trong module đã chọn, bỏ qua `build/` và generated sources.
- Nhóm mọi qualifier variant cùng type/tên thành một logical resource. Ví dụ `layout/activity_home.xml` và `layout-land/activity_home.xml`, hoặc `drawable/ic_search.xml` và `drawable-night/ic_search.xml`, luôn được đổi cùng nhau.
- Drawable gồm file-based resources dưới `res/drawable*`; layout gồm XML dưới `res/layout*`. Không đổi `id`, `mipmap` hoặc resource khai báo inline nếu chưa được yêu cầu.

## Lập plan và preview

- Dùng chung textbox suffix hiện tại và tự lowercase suffix cho resource. Kiểm tra tên Android resource hợp lệ và phát hiện collision trước khi chạy.
- Thêm tab `Resources` hiển thị type, tên cũ/mới, module và số variant/usages.
- Report và verification ghi riêng số drawable/layout đã đổi, file lỗi và reference còn sót.

## Thực thi rename

- Dùng Android/IntelliJ PSI resource references khi có thể để đổi atomically tên file và mọi usage như `R.drawable.*`, `R.layout.*`, `@drawable/...`, `@layout/...`, `<include>` và navigation.
- Cập nhật usages hợp lệ trên toàn project, kể cả usages nằm trong module không được chọn nhưng trỏ tới resource của module được chọn.
- Không sửa bằng replace chuỗi không có ngữ cảnh. Giữ thao tác PSI/file trên EDT write-intent; việc scan và lập index chạy background.
- Nếu bất kỳ qualifier variant nào có tên đích đã tồn tại hoặc read-only, bỏ toàn bộ logical resource và báo conflict; không đổi dở dang hoặc tự tăng số suffix.

## Layout View Binding

View Binding tuân theo Android code generation:

- `activity_home.xml` -> `ActivityHomeBinding`
- `activity_home_inv124.xml` -> `ActivityHomeInv124Binding`
- `activity_home_dn12.xml` -> `ActivityHomeDn12Binding`
- `main_activity_inv124.xml` -> `MainActivityInv124Binding`

Planner tạo mapping binding cũ -> binding mới, cập nhật imports/type usages trong Kotlin, rồi để Gradle sinh lại View Binding class. Không sửa file Java/Kotlin trong `build/generated`. Data Binding và custom `<data class="...">` nằm ngoài phạm vi.

## Kiểm thử

- Unit test biến đổi tên, remove/add suffix, qualifier safety, collision và binding-name conversion.
- Fixture test usages trong Kotlin/XML, `<include>`, navigation và View Binding types.
- Test multi-module: chỉ resource thuộc module được chọn bị đổi; references hợp lệ từ module khác vẫn được cập nhật.
- Chạy `gradlew test`, `gradlew check buildPlugin` và kiểm tra thủ công bằng `runIde` trên project Android có View Binding.

## Quy tắc suffix và collision

Thay suffix và collision là hai bước độc lập. Ví dụ suffix loại bỏ là `dn12`, suffix thêm là `inv124`:

1. `activity_home_dn12` được biến đổi thành `activity_home_inv124`, giống quy tắc class hiện tại.
2. Sau khi tính tên mới, nếu `activity_home_inv124` đã tồn tại thì bỏ qua rename và báo conflict.
3. Nếu tên đã kết thúc bằng `inv124` thì coi là no-op, không thêm lần hai.

Resource suffix luôn là một segment lowercase, nối bằng `_`. Chỉ loại bỏ suffix khi segment cuối khớp chính xác textbox. Ví dụ suffix thêm là `dn12`:

- `activity_home` -> `activity_home_dn12`
- `activity_home_xx14` -> `activity_home_xx14_dn12` nếu suffix cần loại bỏ không phải `xx14`
- `main_activity_inv124` -> `main_activity_inv124_dn12` nếu suffix cần loại bỏ không phải `inv124`; View Binding đổi từ `MainActivityInv124Binding` thành `MainActivityInv124Dn12Binding`
- `main_activity_inv124` -> `main_activity_dn12` nếu suffix cần loại bỏ là `inv124`; View Binding đổi thành `MainActivityDn12Binding`

## Quyết định đã xác nhận

- `Refactor drawables` và `Refactor layouts` mặc định bật; preview không cho chọn từng resource.
- Dùng chung suffix textbox, tự lowercase cho resource và chỉ loại bỏ suffix khớp chính xác.
- Hỗ trợ mọi qualifier variant; bỏ qua `mipmap*`.
- Chỉ hỗ trợ View Binding, dùng tên PascalCase chuẩn sinh từ layout.
- Cập nhật usages trên toàn project, kể cả module không được chọn.
- Collision làm bỏ qua toàn bộ logical resource và được báo trong preview/report.

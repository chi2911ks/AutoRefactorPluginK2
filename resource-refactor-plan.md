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

## Mở rộng tiếp theo: Các loại resource khác

Ngày mai mở rộng refactor cho mọi file-based resource trực tiếp dưới `res/<type>` và các qualifier directory tương ứng. Tiếp tục loại trừ hoàn toàn:

- `values*`
- `font*`
- `mipmap*`
- `raw*`

Phạm vi dự kiến bao gồm `drawable*`, `layout*`, `anim*`, `animator*`, `color*`, `menu*`, `navigation*`, `transition*`, `xml*`, `interpolator*` và các loại file-based resource hợp lệ khác không nằm trong danh sách loại trừ.

Giữ nguyên các quy tắc hiện có:

- Gom mọi qualifier variant cùng module/type/tên thành một logical resource và đổi cùng nhau.
- Dùng suffix chung, tự lowercase, chỉ loại suffix cuối khớp chính xác rồi nối suffix mới bằng `_`.
- Cập nhật references trên toàn project theo đúng type, ví dụ `R.menu.main_menu`, `@menu/main_menu`, `R.navigation.main_nav` và `@navigation/main_nav`.
- Không đổi tên file trong `values*`, nhưng vẫn cập nhật resource references nằm bên trong các file này.
- Nếu target tồn tại hoặc một variant read-only, bỏ qua toàn bộ logical resource và báo trong preview/report.
- Không sửa generated sources.

### Câu hỏi cần xác nhận trước khi triển khai

Không còn câu hỏi chặn triển khai. Các quyết định mới đã được xác nhận:

1. Scanner thu thập mọi logical resource hợp lệ rồi hiển thị trong tab `Resources`. Bảng preview có cột checkbox để người dùng chọn từng resource cần đổi; một checkbox điều khiển toàn bộ qualifier variants của dòng đó. Resources mặc định chưa được chọn.
2. Tự động hỗ trợ cả loại file-based resource mới nếu directory không thuộc `values*`, `font*`, `mipmap*`, `raw*`.
3. Không đổi tên file trong `values*`, nhưng vẫn cập nhật references bên trong chúng khi resource được chọn đổi tên.
4. Với `navigation`, chỉ đổi file cùng `R.navigation`/`@navigation` references; không chủ động đổi Safe Args generated classes như `MainNavDirections`.

### Điều chỉnh implementation hiện tại

- Tổng quát hóa `AndroidResourceType` để lưu directory type động thay vì chỉ `DRAWABLE`/`LAYOUT`.
- Thay regex cố định `layout|drawable` bằng lookup type đã scan, nhưng chỉ thay reference có ngữ cảnh `R.<type>.<name>` hoặc `@<type>/<name>`.
- Đổi resource preview từ `JTable` chỉ đọc sang table model có cột Boolean editable; khi selection thay đổi, cập nhật `ResourceRename.checked` trước execution.
- Phân biệt `checked=false` do người dùng không chọn với resource bị khóa do collision/read-only; dòng bị conflict không cho tick.
- View Binding mapping chỉ áp dụng cho resource type `layout`; các type khác không tạo binding mapping.
- Report phân biệt `selected`, `not selected` và `skipped by conflict`.
- Áp dụng quy tắc `Text to remove` dùng chung trong `refactor-options-expansion-plan.md`: xóa mọi occurrence không phân biệt hoa/thường, dọn/gộp `_`, rồi thêm suffix lowercase nếu tên chưa kết thúc bằng suffix đích.

## String Resource Prefix Refactor

Mở rộng scanner cho riêng `<string name="...">` trong mọi `values*` directory. Không đổi filename `strings.xml` và không refactor `<string-array>` hoặc `<plurals>`.

String key dùng quy tắc prefix riêng:

1. Xóa mọi occurrence của `Text to remove` ở bất kỳ vị trí nào, không phân biệt hoa/thường.
2. Dọn `_` ở hai đầu và gộp nhiều `_` liên tiếp thành một.
3. Chuẩn hóa prefix mới từ suffix add thành lowercase.
4. Nếu key sau khi dọn chưa bắt đầu bằng prefix mới, thêm `<prefix>_` ở đầu. Nếu đã có prefix mới thì không thêm lần hai.
5. Nếu text cũ không xuất hiện, vẫn thêm prefix mới.

Ví dụ remove `inv069`, add `inv125`:

```text
inv069_tv_content_splash_01 -> inv125_tv_content_splash_01
tv_inv069_content           -> inv125_tv_content
tv_content                  -> inv125_tv_content
inv125_tv_content           -> inv125_tv_content
```

Nhóm mọi locale/country variant cùng key thành một logical string resource, ví dụ `values/`, `values-ja/`, `values-vi/`, `values-en-rUS/`. Một checkbox preview mặc định được tích và điều khiển toàn bộ variants; người dùng bỏ tích để loại key khỏi lần refactor.

Cập nhật references trên toàn project:

- `R.string.old_name` -> `R.string.new_name`
- `@string/old_name` -> `@string/new_name`

Nếu key đích tồn tại trong bất kỳ `values*` variant nào của cùng module, khóa checkbox, bỏ qua toàn bộ logical key và báo conflict trong preview/report. Không sửa nội dung dịch của string.

Áp dụng cùng preview và tính nguyên tử cho `<color>` và `<style>`, với option riêng mặc định bật. Style giữ nguyên casing và phân cấp dấu chấm, ví dụ `AppTheme.AdAttribution` -> `inv125_AppTheme.AdAttribution`; cập nhật cả `R.color`, `@color`, `R.style`, `@style` và style parent references.

Nhóm mọi `res/values*/*.xml` theo logical module và filename qua tất cả qualifier thành checkbox riêng, mặc định tích chọn. Vẫn hiển thị file không chứa tag được hỗ trợ. Nếu bỏ tích một nhóm file, khóa và bỏ qua toàn bộ logical `<string>`, `<color>`, hoặc `<style>` xuất hiện trong bất kỳ variant nào của nhóm đó.

### Tests cần bổ sung

- Prefix cũ ở đầu, giữa, cuối, xuất hiện nhiều lần và khác hoa/thường.
- Key không có prefix cũ vẫn nhận prefix mới.
- Key đã có prefix mới không bị thêm lần hai.
- Group/rename đồng thời mọi locale variants.
- Collision ở một locale làm skip toàn bộ logical key.
- Chỉ `<string>` được đổi; `string-array`, `plurals` và filename giữ nguyên.
- Kotlin/Java/XML references được cập nhật.

# Plan: Mở rộng tùy chọn Refactor và Shuffle

## 1. Mục tiêu

- Thay dialog hỏi shuffle sau khi refactor bằng các checkbox cấu hình ngay trong `RefactorDialog`.
- Cho phép chọn độc lập: refactor class, function, variable; shuffle function, variable.
- Mặc định chỉ bật **Refactor class**.
- Mở rộng discovery khỏi nhóm Android component để checkbox đã bật xử lý loại declaration tương ứng trong toàn bộ source project hợp lệ.
- Thêm textbox nhập suffix cũ cần loại bỏ, ví dụ `Inv124`, trước khi tạo tên đích.

Không triển khai mã trong giai đoạn này.

## 2. UI và hành vi dự kiến

Trong phần cấu hình đầu dialog, thêm:

- `Refactor classes` — mặc định bật.
- `Refactor functions` — mặc định tắt.
- `Refactor variables` — mặc định tắt.
- `Shuffle functions` — mặc định tắt.
- `Shuffle variables` — mặc định tắt.
- `Suffix to add` — giữ textbox/combo hiện tại.
- `Existing suffix to remove (optional)` — textbox mới, mặc định rỗng.

Nút **Scan Project** tạo lại preview theo cấu hình hiện tại. Nếu đổi tùy chọn sau khi scan, plan cũ phải bị invalid và yêu cầu scan lại. Nút **OK** chỉ bật khi plan hợp lệ, không có conflict chặn, và có ít nhất một thao tác được chọn. Sau khi refactor thành công, shuffle chạy tự động theo checkbox; xóa hoàn toàn `offerShuffle()` và dialog Yes/No cuối luồng.

## 3. Quy tắc đổi tên đề xuất

Hàm chuẩn hóa tên đích dùng chung cho class/function/property:

1. Chỉ loại bỏ suffix cũ khi tên **kết thúc chính xác** bằng giá trị đã nhập.
2. Loại bỏ tối đa một lần; không replace chuỗi ở giữa tên.
3. Sau đó nối suffix mới: `MainActivityInv124` + remove `Inv124` + add `Inv125` → `MainActivityInv125`.
4. Nếu suffix cũ rỗng thì giữ hành vi nối suffix hiện tại.
5. Nếu tên sau chuẩn hóa đã bằng tên cũ, không tạo rename.
6. Validate suffix không chứa khoảng trắng/ký tự không hợp lệ và chạy conflict detection trên tên cuối.

## 4. Thay đổi kiến trúc dự kiến

### Model

Tạo cấu hình bất biến, ví dụ `RefactorOptions`, chứa suffix thêm/xóa và năm cờ lựa chọn. Gắn snapshot cấu hình vào `RefactorPlan` để preview, executor, report và verification dùng cùng một nguồn dữ liệu.

### Discovery và symbol collection

- Thay `ComponentDiscoverer` giới hạn Activity/Fragment/Dialog bằng discovery declaration toàn project, vẫn loại generated code, build output và library/read-only PSI.
- Tách khái niệm target class khỏi `ComponentType`; model mới phải biểu diễn class thường và đường dẫn/FQN ổn định.
- Checkbox tắt thì không thu thập loại symbol đó; checkbox bật thì thu thập loại đó ở mọi phạm vi hợp lệ: top-level, member và local declaration.
- `Refactor functions` gồm top-level function, member function và local function. `Refactor variables` gồm top-level/member property và local `val`/`var`.
- Không refactor parameter. Constructor parameter chỉ được tính là variable khi khai báo bằng `val` hoặc `var`.
- Giữ các guard K2 hiện có: bỏ SDK/library overrides, callback contract, synthetic accessor collision và generated/read-only declarations.
- Chỉ tạo `fileRenames` khi đổi class và tên file khớp chính xác tên class top-level.

### Plan, conflict và execution

- `RefactorPlanGenerator` lọc từng nhóm theo options và dùng một name transformer chung.
- Executor không được phụ thuộc việc có class rename mới xử lý symbol rename; hiện tại symbol đang được group qua `componentRenames` nên cần tách luồng.
- Conflict detector kiểm tra trùng tên theo đúng owner/scope, target đã tồn tại, keyword và collision sau khi bỏ/thêm suffix.
- Verification nhận toàn bộ plan, kiểm tra class/function/property đã chọn thay vì chỉ `componentRenames`.

### Shuffle

Mở rộng `DeclarationShuffler` nhận hai cờ `shuffleFunctions` và `shuffleVariables`. Chỉ shuffle loại được bật, giữ nguyên anchor, run cùng loại, dependency block và init order. Target mặc định là các file xuất hiện trong plan; không hiển thị prompt sau execution.

## 5. Các bước triển khai sau khi được duyệt

1. Chốt phạm vi declaration và semantics suffix bằng các câu hỏi bên dưới.
2. Thêm `RefactorOptions` và tổng quát hóa model target/plan.
3. Cập nhật dialog, validation, invalidation khi options đổi và preview counts.
4. Tổng quát hóa discovery/collector cho phạm vi đã chốt.
5. Tách plan/executor khỏi giả định Android component và class-first.
6. Thêm shuffle filters; xóa dialog shuffle cuối.
7. Cập nhật conflict detection, verification, report và diagnostic log.
8. Thêm unit/integration tests và kiểm thử bằng `runIde`.

## 6. Test và tiêu chí nghiệm thu

- Mở dialog lần đầu: chỉ `Refactor classes` được bật.
- Mỗi checkbox làm preview/execution chỉ chứa đúng loại đã chọn.
- Có thể refactor function/variable khi class rename tắt.
- Class thường ngoài Android component xuất hiện trong preview.
- Remove/add suffix cho kết quả đúng và chạy lại không tạo suffix kép.
- SDK override, generated/read-only code và synthetic accessor không bị đổi.
- Shuffle function/variable hoạt động độc lập và không còn dialog cuối.
- Rename class cập nhật import/reference/file/XML phù hợp; conflict chặn execution.
- `./gradlew test`, `./gradlew check`, `./gradlew --no-daemon buildPlugin` thành công.

## 7. Quyết định đã xác nhận

- Checkbox bật thì refactor loại symbol tương ứng ở mọi phạm vi source hợp lệ; checkbox tắt thì không refactor loại đó.
- `Refactor variables` không đổi parameter. Local `val`/`var`, property và constructor property khai báo bằng `val`/`var` vẫn thuộc phạm vi variable.
- Chỉ refactor Kotlin; không thu thập declaration Java.
- Class scope chỉ gồm declaration top-level: class, interface, object, enum class và annotation. Loại nested class/object, enum entry, anonymous, local, generated và read-only declaration.
- Suffix cũ dùng chung cho class/function/variable. Nếu tên không có suffix cũ thì vẫn thêm suffix mới.
- Shuffle theo phạm vi đã đề xuất: file có target, hoặc toàn bộ Kotlin source khi chỉ chọn shuffle; không đảo local declaration.
- Khóa **OK** khi không chọn thao tác nào.
- Không lưu options giữa các lần mở; mặc định luôn chỉ bật `Refactor classes`.
- Cho phép chọn `All modules` hoặc nhiều module bằng Ctrl/Shift; các module được chọn giới hạn target scan/refactor/shuffle, nhưng reference từ module khác vẫn được cập nhật.
- Gom các source-set module IntelliJ như `app.main`, `app.test`, `app.androidTest` vào module logic `app`; chọn `app` sẽ scan hợp content roots của cả nhóm.

## 8. Trạng thái

Đã triển khai. Unit test bao phủ defaults, thay suffix, chống suffix kép, symbol rename độc lập với class và shuffle-only target selection.

IntelliJ `RenameProcessor` được cấu hình không hiển thị secondary usage preview; bấm **OK** trong plugin là lần xác nhận duy nhất.

# AutoRefactorPluginK2 — K2 Analysis API Variant & Rename Fixes

> **Status:** Implemented & building (2026-07-02)
> **Build:** `cd D:\VuaCode\AutoRefactor\AutoRefactorPluginK2 && ./gradlew --no-daemon buildPlugin` → BUILD SUCCESSFUL
> **Artifact:** `build/distributions/AutoRefactorPluginK2-1.0.1-SNAPSHOT.zip`
> **Base:** copy của `AutoRefactorPlugin`, chuyển từ reflection sang **Kotlin K2 Analysis API** trực tiếp.

Đây là biến thể K2 của [android-studio-plugin-refactor](android-studio-plugin-refactor.md). Tài liệu này ghi lại **mọi thay đổi so với bản gốc** trong session 2026-07-02.

---

## 1. Chuyển sang K2 (bỏ né K2)

Bản gốc cố tình né K2 (không depends plugin Kotlin, reflection `Class.forName`, walk superClass thủ công). Bản K2 làm ngược lại:

| Hạng mục | Bản gốc | Bản K2 |
|----------|---------|--------|
| Platform | 2023.3 (233) | **2025.1 (251)**, until-build 261.* |
| Gradle plugins | kotlin 1.9.22, ijp 2.0.1 | **kotlin 2.1.0, ijp 2.2.1**, jvmToolchain 21 |
| depends | platform + java | + **`org.jetbrains.kotlin`** |
| plugin.xml | — | **`<supportsKotlinPluginMode supportsK2="true"/>`**, id `com.org.refactor.plugin.k2` |
| Kotlin PSI | reflection `Class.forName` | **import `org.jetbrains.kotlin.psi.*` trực tiếp** |
| Ngữ nghĩa | heuristic chuỗi | **`analyze{}` K2 Analysis API** (`psi/K2Analysis.kt`) |

**Lý do target 251:** Analysis API đổi tên `Kt*`→`Ka*` (`KtAnalysisSession`→`KaSession`, `getSymbol()`→`.symbol`) từ ~2024.2. Build với 233 rồi chạy IDE 2026 sẽ `NoClassDefFoundError`.

### `psi/K2Analysis.kt` (file mới) — mọi lời gọi `analyze{}` tập trung, đều có fallback cấu trúc:
- `hasBackingField(prop)` — lọc computed property (`KaKotlinPropertySymbol.hasBackingField`).
- `overridesName(fn, target)` — override graph `allOverriddenSymbols`.
- `overridesProjectDeclarationNamed(fn, target)` — **chỉ true nếu base override nằm trong project** (`ov.psi?.isWritable`). Xem mục 6.
- `siblingDependencies(prop, siblings)` — `mainReference.resolveToSymbols()` tìm property phụ thuộc (cho shuffle). Xem mục 3.

---

## 2. Tính năng mới: Xáo trộn thứ tự khai báo (`shuffle/DeclarationShuffler.kt`)

Sau refactor thành công, `AndroidRefactorAction` hiện `Messages.showYesNoDialog` hỏi có xáo trộn không; Yes → chạy engine.

Quy tắc (đã chốt với user):
- **Phạm vi:** chỉ file `.kt` có trong plan (componentRenames + symbolRenames sourceFile).
- **Tách nhóm:** biến trộn biến, hàm trộn hàm — xáo theo từng **run cùng loại liền kề**; giữ nguyên vùng property/function và cách xen kẽ.
- **Gom khối phụ thuộc:** property tham chiếu nhau (qua `siblingDependencies`) gom thành block nguyên khối di chuyển cùng nhau, giữ thứ tự nguồn → không vỡ init order. Dùng union-find.
- **Override lifecycle:** có xáo (hàm luôn lazy).
- **ANCHOR (bất động + rào chắn):** `companion object`, object/class lồng nhau, `init{}`, constructor phụ, enum entry. Property không vượt qua ANCHOR.
- **Ghi file:** giữ separator (dòng trống/comment) theo vị trí, chỉ hoán vị text declaration; nhiều edit áp dụng từ dưới lên trong 1 `WriteCommandAction`.
- **Giới hạn đã biết:** phụ thuộc eager gián tiếp qua lời gọi hàm thành viên không phát hiện được.

---

## 3. Fix: override bị double-suffix + `super.x()` không đổi

**Triệu chứng:** `override fun bindView` → `bindViewV2V2`, và `super.bindView()` không đổi.

**Nguyên nhân:** ở K2 mode `OverridingMethodsSearch` bắt được Kotlin override → override bị xử lý 2 lần (OverridingMethodsSearch ghi reps + `findKotlinOverrides` **sửa file ngay giữa chừng**), làm lệch offset toàn bộ reps trong file.

**Fix (RefactorExecutor):**
- `findKotlinOverrides` → `collectKotlinOverrideReps`: **đóng góp vào `reps`**, không sửa ngay. Mọi thay đổi qua một vòng apply duy nhất (dedup + sort offset giảm dần).
- Guard theo start-offset chống trùng với reps của OverridingMethodsSearch.
- Thêm hàm nguồn vào tập search để bắt `super.x()`.

---

## 4. Fix: khai báo base không đổi, override đổi (via light method)

**Triệu chứng:** `abstract fun setBinding` (base) giữ nguyên, nhưng `override fun setBinding` (con) → `setBindingV2` → vỡ override.

**Nguyên nhân:** `findElement` trả **light `PsiMethod`** cho hàm Kotlin; `addDecl` lấy range từ light method (không map đúng source với abstract/generic) → base không được ghi; override thì đổi qua PSI thật trong `collectKotlinOverrideReps`.

**Fix (RefactorExecutor):**
- `findElement`/`findInKtFile` **kind-aware, Kotlin-first**: symbol FUNCTION → `KtNamedFunction` thật (ưu tiên khai báo non-override); PROPERTY → `KtProperty`. Không lấy light method theo tên nữa.
- `findInKtFile` **giới hạn trong đúng class** (khớp `fqName` với `parentFqn`), không quét cả file.
- `resolveSearchElements`: dùng `LightClassUtil.getLightClassPropertyMethods` (accessor chính xác) + `KtNamedFunction.toLightMethods()`; `OverridingMethodsSearch` chạy trên PsiMethod trong searchElements.

---

## 5. Fix: va chạm tên `var binding` ↔ `fun setBinding`

**Triệu chứng:** đổi property `binding` → 33 edit (bình thường ~11), và pass `setBinding` bị `SKIP: target already present`.

**Nguyên nhân:** `lateinit var binding` sinh setter tổng hợp tên `setBinding` **trùng** hàm thật `fun setBinding`. `ReferencesSearch` theo tên kéo usage của hàm thật vào pass đổi `binding`; nhánh apply `rt == "set$capOld"` ghi `setBinding`→`setBindingV2`. Rồi pass `setBinding` thấy đã tồn tại → skip.

**Fix (RefactorExecutor):**
- `resolveSearchElements` (property): **bỏ qua accessor trùng tên với một hàm thật trong cùng class** (kiểm `containingClassOrObject.declarations`).
- Đảo guard "đã đổi": resolve theo **tên CŨ trước**; chỉ coi "already renamed" khi tên cũ thực sự biến mất (một `setBindingV2` sót/ở nơi khác không chặn nhầm).

---

## 6. Fix (rule mới): không đổi override của SDK/anonymous object

**Triệu chứng:** `override fun handleOnBackPressed()` trong `object : OnBackPressedCallback(true)` bị → `handleOnBackPressedINV122` → framework không gọi được.

**Nguyên nhân:** `handleOnBackPressed` là override method **SDK androidx** trong anonymous object; `collectKotlinOverrideReps` quét đệ quy mọi `KtNamedFunction` (kể cả trong anonymous object) và đổi tên.

**Fix:**
- `K2Analysis.overridesProjectDeclarationNamed(fn, target)`: chỉ true nếu base được override **khai báo trong project** (`ov.psi?.isWritable == true`); override của SDK/library (psi read-only) → false. Fallback: bỏ qua override nằm trong `KtObjectLiteralExpression`.
- `collectKotlinOverrideReps` dùng hàm này thay `overridesName`.
- Thêm `handleOnBackPressed` vào callback skip set (belt-and-suspenders; **không** thêm tên chung như onFinish/run vì trùng method user).

---

## 7. Chẩn đoán

`RefactorExecutor.execute` ghi `<projectRoot>/.autorefactor-symbols.log` — mỗi symbol 1 dòng: `OK/NOOP/SKIP/FAIL <old>-><new> [KIND] via <element>: N edit(s)` kèm lý do skip. Dùng để debug ground-truth.

---

## Golden Rules bổ sung (so với plan gốc)

7. ✅ Resolve rename target theo **SymbolKind** tới **PSI Kotlin thật**, không dùng light method theo tên (tránh va chạm accessor/hàm).
8. ✅ Mọi thay đổi override đi qua **`reps`** rồi apply 1 lần (không sửa file giữa lúc thu thập offset).
9. ✅ **Không rename override của khai báo ngoài project** (SDK/library) — kiểm `overridesProjectDeclarationNamed`.
10. ✅ Khi đổi property, **bỏ accessor trùng tên hàm thật** khỏi tập search.
11. ✅ Guard idempotent: resolve theo tên cũ trước; chỉ skip khi tên cũ đã biến mất.

---

## Build

```bash
cd D:\VuaCode\AutoRefactor\AutoRefactorPluginK2
./gradlew --no-daemon buildPlugin
```
Lần đầu tải platform 2025.1 (~1.5GB). Test hành vi thực bằng `runIde` hoặc cài zip + restart IDE.

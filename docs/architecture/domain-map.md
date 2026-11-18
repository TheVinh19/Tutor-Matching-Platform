# Ranh giới nghiệp vụ và bản đồ giao diện

Tài liệu này mô tả toàn bộ phạm vi dự án ở mức thiết kế cho khung repo. Các module, route nghiệp vụ và sự kiện bên dưới là bản đồ triển khai dự kiến, chưa phải chức năng đã có. Không tạo thêm service cho mỗi tính năng và không bổ sung code nghiệp vụ trong giai đoạn dựng khung.

## Nguồn và cách đối chiếu

| Nguồn trong `Tai lieu/` | Nội dung dùng để thiết kế |
|---|---|
| `Yeu_cau_du_an_gia_su_v1.2.docx` | Nguồn chính sách chính, mục 15 với P01–P15. Dùng để đối chiếu điều kiện và các mốc thời gian chi tiết. |
| `Vai_tro_va_quyen_nen_tang_gia_su_v1.2.docx` | Các bảng “Hồ sơ và kế hoạch học”, “Buổi học và quản trị”, “Các quyền cần đối chiếu”: phạm vi Người thuê, Gia sư, Admin và quyền theo chủ đơn/vụ việc. |
| `Luong_nghiep_vu_va_trang_thai_du_an_gia_su_v1.2.docx` | Các mục kế hoạch và thu tiền, gia hạn, đổi lịch, buổi học, đóng/hủy kỳ, thu chi, Hạng/EXP/nhận xét, khiếu nại và bằng chứng. |
| `Bang_chung_va_chi_so_van_hanh_v1.2.docx` | Dữ liệu cần lưu để kiểm chứng, cách phân biệt các chỉ số vận hành, khoản tiền, EXP, nhận xét và tranh chấp. |
| `Kien_truc_va_tich_hop_nen_tang_gia_su.docx` | Stack và các tích hợp của dự án; AI Career Assistant/Gemini có phạm vi cần đặc tả riêng. Chưa biến placeholder thành chức năng tư vấn hay gợi ý gia sư. |

Các quy tắc v1.2 đã thống nhất trong tài liệu nghiệp vụ. Cách chia service, tên module và route dưới đây là quyết định tổ chức khung repo; chúng không thay đổi chính sách P01–P15. Nếu cách chia phát hiện mâu thuẫn với chính sách, đối chiếu nguồn chính trước khi thêm nghiệp vụ.

## Cấu trúc tổng thể

| Đường dẫn | Vai trò |
|---|---|
| `server/api-gateway/` | Định tuyến API và chính sách truy cập đầu vào. Service vẫn kiểm tra quyền và quyền sở hữu dữ liệu. |
| `server/services/identity-service/` | Tài khoản, xác minh email, đăng nhập, phiên, vai trò và hạn chế tài khoản. |
| `server/services/tutor-service/` | Hồ sơ gia sư, danh mục, xét duyệt, tìm kiếm, nhận xét, EXP và Hạng. |
| `server/services/booking-service/` | Kết nối/trao đổi, lịch trống, kế hoạch, giữ lịch, kỳ học, buổi học và vụ việc. |
| `server/services/payment-service/` | Giá, QR mô phỏng, giao dịch, sổ tiền, hoàn/thu hồi/chi mô phỏng. |
| `server/services/notification-service/` | Thông báo web, email và lịch sử gửi. |
| `server/services/ai-service/` | Chỉ có README mô tả phần chờ đặc tả AI; chưa có ứng dụng chạy hoặc API nghiệp vụ. |
| `web/src/app/` | Next.js App Router cho giao diện công khai và ba vai trò. |
| `mock/` | Giả lập nhà cung cấp thanh toán và callback khi triển khai luồng tiền. |
| `infra/` | Hạ tầng local, cấu hình chạy, database, Kafka, Redis, Mailpit và volume. |
| `docs/` | Kiến trúc, phạm vi nghiệp vụ, hợp đồng tích hợp và hướng dẫn chạy/kiểm tra. |

Tên service mới không kéo theo đổi tên database, DB username hoặc biến môi trường legacy. `identity-service` tiếp tục dùng `account_db`; `booking-service` tiếp tục dùng `learning_db`. Giữ các giá trị username/env legacy theo cấu hình hiện tại khi đổi đường dẫn.

## Chủ sở hữu và module

### Identity service

Module trong khung: `registration`, `verification`, `authentication`, `accounts`, `restrictions`. Phiên thuộc `authentication`; vai trò thuộc `accounts`.

- Sở hữu danh tính, email, thông tin đăng nhập, vai trò, phiên và trạng thái truy cập tài khoản.
- Một email chỉ một vai trò theo P01. Giao diện `/learner` đại diện tài khoản Người thuê; đây không phải vai trò thứ tư.
- Không trao quyền xác nhận/nghiệm thu cho người học khác tài khoản đứng tên đơn.
- Nhận kết quả hạn chế tài khoản sau xử lý nghiệp vụ; bằng chứng và căn cứ của vụ việc vẫn thuộc booking.

### Tutor service

Module trong khung: `profiles`, `catalog`, `approval`, `media`, `search`, `reviews`, `experience`, `ranks`.

- Sở hữu hồ sơ gia sư, môn/cấp học/địa bàn, trạng thái duyệt, video minh họa, điều kiện và lịch sử Hạng.
- Sở hữu danh mục dùng cho hồ sơ/tìm kiếm và quan hệ môn/cấp học đã được duyệt của từng gia sư.
- Sở hữu sổ EXP có thưởng, phạt, đảo điểm và mã nguồn sự kiện để chống lặp; theo dõi số lượt gia hạn và distinct tài khoản Người thuê.
- Sở hữu nhận xét gắn kỳ học, phản hồi của gia sư, bản gốc và lịch sử ẩn/khôi phục. Điều kiện được nhận xét đến từ booking.
- Tìm kiếm theo môn, cấp học, Hạng, hình thức, địa bàn và lịch; không có lọc giá hoặc thuật toán xếp hạng trong v1.2.
- Có thể đọc dữ liệu lịch từ booking hoặc bản chiếu phục vụ tìm kiếm. Chỉ booking được quyết định lịch còn đặt được tại thời điểm chốt.
- Không sở hữu bảng giá hay sổ tiền. Hạng hiện hành là dữ liệu đầu vào để payment xác định giá theo Hạng × hình thức.

### Booking service

Module trong khung: `connections`, `conversations`, `availability`, `plans`, `holds`, `periods`, `sessions`, `rescheduling`, `renewals`, `reports`, `makeup`, `cases`, `evidence`, `appeals`.

- Sở hữu kết nối và trao đổi liên quan, lịch nhận dạy, kế hoạch có phiên bản, xác nhận của hai bên, lịch/buổi được sinh và các ngoại lệ.
- Sở hữu tất cả quyết định giữ/chốt/giải phóng lịch, kể cả giữ mới trong đổi lịch. Các thao tác này dùng chung database và transaction của booking.
- Sở hữu trạng thái kỳ/buổi, giờ dự kiến/thực tế, báo cáo, lịch sử sửa, nghiệm thu/phản đối, cam kết bù và ngày kết thúc ban đầu/hiện hành.
- Sở hữu sự cố, yêu cầu thay gia sư, vụ việc, hồ sơ bằng chứng, kháng nghị, quyết định nghiệp vụ và phần nghĩa vụ còn mở.
- Nhận giá từ payment, lưu snapshot giá và số tiền gốc cùng phiên bản kế hoạch. Nhận kết quả thanh toán để chốt kế hoạch/đổi lịch/gia hạn.
- Xác định thời điểm kỳ hoàn tất nghĩa vụ, được nhận xét và gia hạn đủ điều kiện. Phát sự kiện cho tutor, payment và notification khi triển khai tích hợp.

Tên `booking-service` bao gồm toàn bộ vòng đời đặt học và thực hiện kỳ học, không chỉ thao tác đặt lịch đầu tiên.

### Payment service

Module trong khung: `pricing`, `requests`, `callbacks`, `ledger`, `refunds`, `reconciliation`, `payouts`, `recoveries`.

- Sở hữu bảng giá Hạng × hình thức, cách tính tiền và tỷ lệ chia cố định 85/15 theo chính sách; lưu phiên bản giá để đối chiếu.
- Sở hữu yêu cầu thanh toán, số tiền kỳ vọng cố định, mã giao dịch, hạn giao dịch và kết quả đối soát tiền nhận thực tế.
- Sở hữu sổ tiền thu/hoàn/thu hồi/chi. Tách thu nhập thực dạy và bồi hoàn Người thuê nghỉ; không coi toàn bộ thu trước là thu nhập.
- Dựa vào quyết định nghiệp vụ có căn cứ từ booking để ghi nhận khoản hưởng, khoản giữ tranh chấp hoặc hoàn. Không tự kết luận lỗi buổi học.
- Thanh toán và chi trả trong phạm vi dự án là mô phỏng; kết quả hoàn sau chi trả vẫn cần lưu phần xử lý thủ công theo chính sách.

### Notification service

Module trong khung: `inbox`, `email`, `templates`, `delivery`, `consumers`.

- Sở hữu hộp thông báo web, nội dung gửi, địa chỉ nhận, các lần gửi và kết quả gửi.
- Cung cấp dấu vết thông báo để booking áp dụng các hạn nghiệm thu/bằng chứng; hợp đồng thời điểm bắt đầu timer phải được định nghĩa rõ trước khi thêm nghiệp vụ.
- Không quyết định tự nghiệm thu, lỗi gia sư, điều kiện nhận tiền hay đóng vụ việc.

### AI placeholder

Giữ `server/services/ai-service/README.md` làm nơi ghi phạm vi chờ đặc tả của AI Career Assistant/Gemini theo tài liệu kiến trúc. Chưa tạo service chạy, schema, route giao diện, quyền truy cập dữ liệu hoặc chức năng gợi ý gia sư. Không đưa LMS, SSO, skill-gap hay nghiệp vụ tuyển dụng từ ví dụ dự án khác vào khung này.

## Trao đổi xuyên service

Các tên sự kiện/API phải được đặc tả khi triển khai; bảng này mô tả điều kiện và chủ sở hữu, chưa phải hợp đồng kỹ thuật cuối cùng.

| Tình huống | Trách nhiệm phối hợp |
|---|---|
| Gửi/chốt kế hoạch | Tutor cung cấp Hạng/hồ sơ hợp lệ; payment cung cấp giá; booking lưu snapshot, phiên bản và giữ lịch. Payment xác nhận tiền; booking chốt lịch. |
| Đổi lịch có thu bù | Booking giữ cả lịch cũ và mới trong lúc payment xử lý. Thành công mới chuyển lịch nguyên tử; thất bại/hết hạn bỏ giữ mới. Tiền muộn được payment xử lý hoàn/đảo theo P03/P08. |
| Nghiệm thu và khoản hưởng | Booking quyết định trạng thái buổi cùng căn cứ; payment ghi loại khoản và điều kiện chi tương ứng; tutor ghi EXP nếu đủ điều kiện. |
| Gia hạn | Booking chỉ xác nhận đủ điều kiện thưởng khi kỳ cũ hoàn tất nghĩa vụ và kỳ mới trả đủ. Tutor ghi +50 EXP và một lượt gia hạn cùng mã nguồn; không thưởng lặp khi hai điều kiện đến ở hai thời điểm. |
| Nhận xét | Booking cung cấp kỳ và thời điểm đủ điều kiện; tutor kiểm tra chủ đơn, hạn 14 ngày và tính duy nhất trước khi lưu. |
| Vụ việc và hạn chế tài khoản | Booking lưu hồ sơ/quyết định; payment giữ hoặc xử lý tiền liên quan; tutor cập nhật EXP/Hạng nếu quyết định yêu cầu; identity áp dụng hạn chế truy cập. |
| Thông báo có timer | Service nghiệp vụ yêu cầu thông báo; notification lưu kết quả gửi; service nghiệp vụ sở hữu timer, điều kiện hết hạn và hành động tiếp theo. |

Mỗi service là chủ database riêng. Không giả định transaction chung xuyên database. Khi triển khai các luồng liên quan lịch/tiền/EXP, cần outbox, xử lý lặp an toàn và đối soát. Kafka không thay thế transaction bảo vệ lịch; Redis không phải nguồn quyết định lịch hoặc tiền.

## Route map frontend

Đây là các route dự kiến trong `web/src/app/`. Route group của Next.js có thể dùng để tổ chức layout mà không đổi URL. Bảo vệ route giao diện chỉ phục vụ trải nghiệm; backend vẫn phải kiểm tra vai trò và quyền đối với đơn/buổi/vụ việc cụ thể.

| Phạm vi | URL dự kiến | Nội dung chính |
|---|---|---|
| Công khai | `/`, `/tutors`, `/tutors/[tutorId]` | Giới thiệu, tìm kiếm và hồ sơ/video/nhận xét đã được phép công khai. |
| Tài khoản | `/login`, `/register`, `/verify-email`, `/forgot-password`, `/reset-password` | Đăng ký, xác minh và truy cập tài khoản. |
| Người thuê | `/learner`, `/learner/profile` | Tổng quan và thông tin tài khoản đứng tên đơn. |
| Người thuê | `/learner/connections`, `/learner/connections/[id]` | Yêu cầu kết nối và trao đổi với gia sư. |
| Người thuê | `/learner/plans`, `/learner/plans/[id]` | Xem phiên bản kế hoạch, xác nhận và thanh toán. |
| Người thuê | `/learner/periods`, `/learner/periods/[id]` | Theo dõi nghĩa vụ, gia hạn, đóng kỳ và nhận xét đủ điều kiện. |
| Người thuê | `/learner/schedule`, `/learner/sessions/[id]` | Lịch học, Meet/liên hệ, yêu cầu đổi lịch, xác nhận/phản đối và báo sự cố. |
| Người thuê | `/learner/payments`, `/learner/payments/[id]` | Giao dịch, QR cố định số tiền và theo dõi hoàn. |
| Người thuê | `/learner/cases`, `/learner/cases/[id]`, `/learner/notifications` | Vụ việc, bằng chứng, kháng nghị, quyết định và thông báo liên quan. |
| Gia sư | `/tutor`, `/tutor/profile` | Tổng quan và khai/cập nhật hồ sơ. |
| Gia sư | `/tutor/profile/subjects`, `/tutor/profile/evidence`, `/tutor/profile/videos` | Môn/cấp học, minh chứng và video cần xét duyệt. |
| Gia sư | `/tutor/availability` | Khai/sửa phần lịch còn trống. |
| Gia sư | `/tutor/connections`, `/tutor/connections/[id]` | Kết nối và trao đổi. |
| Gia sư | `/tutor/plans`, `/tutor/plans/[id]`, `/tutor/periods`, `/tutor/periods/[id]` | Lập/gửi kế hoạch, gia hạn và theo dõi nghĩa vụ. |
| Gia sư | `/tutor/schedule`, `/tutor/sessions/[id]` | Lịch dạy, đổi lịch, Meet, giờ thực tế, báo cáo và cam kết bù. |
| Gia sư | `/tutor/cases`, `/tutor/cases/[id]` | Báo sự cố, giải trình, bằng chứng và kháng nghị. |
| Gia sư | `/tutor/earnings`, `/tutor/rank`, `/tutor/reviews`, `/tutor/notifications` | Hai loại khoản hưởng, đối soát, EXP/Hạng, phản hồi nhận xét và thông báo. |
| Admin | `/admin`, `/admin/accounts` | Tổng quan và xử lý tài khoản theo quyền. |
| Admin | `/admin/tutors`, `/admin/tutors/[id]`, `/admin/catalog` | Duyệt hồ sơ/môn/cấp học/video và quản lý danh mục. |
| Admin | `/admin/pricing`, `/admin/ranks` | Quản lý bảng giá và xét Hạng theo toàn bộ điều kiện. |
| Admin | `/admin/periods`, `/admin/periods/[id]`, `/admin/sessions/[id]` | Tra cứu/hỗ trợ kỳ và buổi học trong phạm vi được phép. |
| Admin | `/admin/cases`, `/admin/cases/[id]` | Tiếp nhận, xác minh, bằng chứng, thay gia sư, quyết định và kháng nghị. |
| Admin | `/admin/payments`, `/admin/refunds`, `/admin/reconciliation`, `/admin/payouts` | Theo dõi tiền, hoàn/thu hồi, đối soát và chi mô phỏng. |
| Admin | `/admin/reviews`, `/admin/reports`, `/admin/audit` | Ẩn/khôi phục nhận xét vi phạm, chỉ số và nhật ký truy vết. |

Các thao tác xác nhận, checkout, đổi lịch, báo cáo, phản đối, gia hạn, viết nhận xét, nộp bằng chứng và kháng nghị có thể nằm trong trang chi tiết tương ứng. Không bắt buộc tạo page riêng cho từng nút thao tác. Không có vai trò hoặc khu vực giao diện thứ tư cho người học khác tài khoản Người thuê.

## Mock và tích hợp local

- `mock/payment-provider/`: mô phỏng callback thanh toán thành công, hết hạn, tiền đến muộn, sai số tiền và callback lặp. Thêm kịch bản hoàn/chi giả khi triển khai các nghiệp vụ đó. Khi khung chưa có luồng tiền, chỉ cần ghi rõ giao diện và kịch bản dự kiến.
- Mailpit: nhận email local từ notification; không cần thêm một email mock riêng.
- Meet: gia sư lưu/cập nhật liên kết ở kế hoạch hoặc buổi học. Không cần mock Meet hoặc tích hợp API Meet trong phạm vi hiện tại.
- Không cần LMS, SSO, skill-gap, recruitment mock hoặc mock cho chức năng Gemini chưa đặc tả.

## Bằng chứng, dữ liệu riêng và truy vết

Booking sở hữu metadata bằng chứng: mã đơn/buổi/vụ, bên nộp, thời điểm máy chủ, các lần nộp, hạn nộp, quyền truy cập, liên kết file, quyết định và kháng nghị. File có thể đi qua storage adapter với volume local ở môi trường phát triển; chưa cần một service lưu bằng chứng độc lập. Không đặt bằng chứng riêng trong thư mục public của frontend. Phương án object storage triển khai thật được quyết định riêng.

Lưu phiên bản kế hoạch, xác nhận của tài khoản đứng đơn, tiền gốc, mã đơn/giao dịch duy nhất, hạn giữ, ngày kết thúc ban đầu/hiện hành và lịch sử thay đổi. Giờ thực tế cần thời điểm máy chủ và lịch sử sửa; cam kết bù cần số phút thiếu, buổi nguồn, buổi nhận, trao đổi/chấp thuận và số phút bù đã xác nhận. Không tự suy ra đã bù chỉ vì không phản đối.

Vụ đang mở phải giữ chứng cứ và lịch sử xử lý. Không xóa hoặc ép đóng để đạt mốc rà soát. Thời hạn lưu chi tiết, trường hợp vụ việc kéo dài và chính sách truy cập đối chiếu P14 trong tài liệu yêu cầu chính. Nhật ký từng service cần liên kết được bằng mã đơn/buổi/vụ/giao dịch và mã sự kiện, không đẩy toàn bộ dữ liệu riêng vào thông báo hoặc log dùng chung.

## Các ràng buộc cần giữ khi triển khai

1. **Chủ thể có quyền:** chỉ tài khoản đứng tên đơn xác nhận kế hoạch và xác nhận/phản đối hoàn thành. Gia sư gửi kế hoạch là xác nhận phía gia sư; admin không xác nhận thay Người thuê. Nguồn: bảng quyền hồ sơ/kế hoạch và buổi học; P01/P04.
2. **Giữ kế hoạch và tiền:** giữ kế hoạch 24 giờ, chặn trước buổi đầu 12 giờ, giao dịch 10 phút. QR có số tiền cố định; đối chiếu tiền nhận thực tế và hạn, không tin ảnh hoặc số tiền khách tự nhập. Nguồn: luồng “Kế hoạch và thu tiền”; P02/P03.
3. **Một chủ lịch:** giữ/chốt/giải phóng phải do booking thực hiện bằng ràng buộc và transaction trong cùng database. Lịch mới tạm giữ không cho người khác đặt; đổi lịch có thu bù vẫn giữ lịch cũ. Thành công chuyển nguyên tử và mới cập nhật ngày kết thúc; thất bại/hết hạn bỏ giữ mới. Nguồn: luồng “Đổi lịch”; P03/P08/P12.
4. **Gia hạn không chen ca:** trao đổi sớm hoặc kỳ gia hạn chưa quyết định chưa tạo quyền ưu tiên hay giữ lịch. Kỳ mới chỉ chốt sau xác nhận và trả đủ. Nguồn: luồng “Gia hạn”; P09/P10.
5. **Thời gian buổi học:** ca dự kiến 2 giờ và cách nhau 1 giờ. Giờ thực tế linh hoạt khi không phản đối; ngắn giờ không tự kết luận lỗi hoặc hoàn/phạt. Chỉ xử lỗi khi Người thuê yêu cầu và có xác minh/kết luận. Nguồn: luồng “Buổi học”, “Thay gia sư, giờ học thực tế và cam kết bù”; P04/P08.
6. **Nghiệm thu:** Người thuê có 24 giờ từ thông báo để xác nhận/phản đối. Không tự nghiệm thu khi có vụ mở; vắng không báo vẫn là sự cố riêng. Gia sư không được sửa lùi thời gian để lấy EXP. Nguồn: bảng quyền buổi học và luồng “Buổi học”; P04/P09.
7. **Tiền thực dạy khác bồi hoàn:** tách khoản thực dạy và khoản bồi hoàn Người thuê nghỉ; mỗi loại gia sư hưởng 85%, nền tảng 15% sau làm tròn phần gia sư. Bồi hoàn không được đổi thành thực dạy hoặc thưởng EXP buổi. Tách chờ/đủ điều kiện/đã chi và khoản tranh chấp. Nguồn: luồng “Người thuê nghỉ”, “Thu chi tháng”, bảng chỉ số; P05/P11.
8. **Đối soát/chi:** lịch chốt ngày 8, chi ngày 10 và giữ đủ điều kiện 3 ngày theo P11. Không dùng tổng tiền thu trước làm thu nhập; lưu phải thu/đã thu, phải hoàn/đã hoàn, cần thu hồi/đã thu hồi riêng. Nguồn: luồng “Thu chi tháng” và bảng chỉ số.
9. **EXP/Hạng:** +10 chỉ cho buổi nghiệm thu hợp lệ bắt đầu đúng phút dự kiến; +30 cho kỳ đủ nghĩa vụ; +50 và một lượt cho gia hạn đủ điều kiện. Lệch phút bắt đầu chỉ mất thưởng buổi, không tự giảm tiền hoặc tỷ lệ. EXP tối thiểu 0, có thưởng/phạt/đảo và chống lặp. Mất điều kiện duy trì Hạng vẫn phải xét lại dù còn đủ EXP; admin quyết định trong 2 ngày làm việc và lưu căn cứ/ngày hiệu lực. Nguồn: luồng “Hạng, EXP và nhận xét”, bảng quyền và bảng chỉ số; P09/P10/P13.
10. **Hai tỷ lệ khác nhau:** tỷ lệ vận hành là thực dạy nghiệm thu trên tổng buổi đến hạn trong kỳ, gồm hủy từng chốt trong mẫu số. Tỷ lệ xét Hạng dựa lịch sử có kết quả, loại trừ lỗi Người thuê/ngoại lệ không lỗi; vụ ảnh hưởng chưa xử lý thì chưa duyệt. Mẫu số 0 hiển thị chưa có dữ liệu; riêng xét Hạng chưa đạt. Nguồn: bảng “Chỉ số vận hành”; P10.
11. **Người thuê distinct:** đếm tài khoản đứng tên có kỳ hoàn tất nghĩa vụ, không gọi là số học sinh; nhiều người học dùng cùng tài khoản vẫn là một Người thuê. Nhiều gia hạn cùng Người thuê vẫn có thể tạo nhiều lượt hợp lệ. Nguồn: luồng “Gia hạn” và bảng chỉ số; P09/P10.
12. **Nhận xét:** chỉ kỳ `HOAN_TAT_NGHIA_VU` hoặc `KET_THUC_CO_HOAN_MOT_PHAN` mở quyền trong 14 ngày. Nhận xét chữ tự nguyện, không sao, không sửa và không đồng nghĩa thưởng EXP. Admin chỉ ẩn/khôi phục toàn nhận xét vi phạm, giữ bản gốc/lý do/người/thời điểm xử lý. Nguồn: bảng quyền và bảng bằng chứng; P07/P12.
13. **Vụ việc và kháng nghị:** gia sư có 3 ngày/72 giờ nộp bằng chứng sau thông báo. Không nộp thì admin mặc định theo Người thuê trong phạm vi vụ; có bằng chứng phải đối chiếu hai phía. Giữ mở vụ/kỳ liên quan nếu nghĩa vụ hoặc kháng nghị còn tồn; không có hạn tổng buộc kết thúc, mốc rà soát 14 ngày không ép đóng. Nguồn: luồng “Đóng hoặc hủy kỳ”, “Khiếu nại và bằng chứng”, bảng bằng chứng; P07/P12/P14.
14. **Mất liên lạc:** phạm vi các đơn/buổi có rủi ro do admin xác định và liệt kê kèm căn cứ; thời hạn 24 giờ/tối thiểu hai lần gọi theo P11. Không tự hủy toàn bộ dữ liệu của gia sư vì một vụ. Nguồn: luồng “Gia sư không thực hiện” và bảng quyền; P06/P11.

## Những phần chưa được khung repo quyết định

ERD, ràng buộc khóa lịch cụ thể, API và event schema, lịch chạy timer, cơ chế retry/outbox, cách chứng minh thông báo bắt đầu timer, lưu trữ file triển khai thật và đặc tả AI cần được thiết kế ở bước nghiệp vụ tương ứng. Bản đồ này xác định nơi sở hữu trách nhiệm; không thay thế các quyết định kỹ thuật hoặc kiểm thử của từng luồng.

# tutor-service migrations

`V1__tutor_profiles.sql`: danh mục môn/cấp học/địa bàn, hồ sơ gia sư, lịch sử duyệt, nhận xét. Never edit an applied migration.
`V2__history_geography_primary_subjects.sql`: thêm Lịch sử, Địa lý; cột `subjects.primary_school` (chỉ Toán, Văn, Anh được dạy Tiểu học).
Dữ liệu demo nằm ở `../demo/` và chỉ chạy khi bật Spring profile `demo`.

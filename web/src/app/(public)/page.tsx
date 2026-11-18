import Link from "next/link";
export default function Home() {
  return <main><p>Đồ án tốt nghiệp</p><h1>Nền tảng gia sư</h1>
    <p>Khung phát triển. Các chức năng nghiệp vụ chưa được triển khai.</p>
    <nav aria-label="Khu vực phát triển"><ul>
      <li><Link href="/learner">Người thuê / học viên</Link></li>
      <li><Link href="/tutor">Gia sư</Link></li>
      <li><Link href="/admin">Quản trị viên</Link></li>
    </ul></nav></main>;
}

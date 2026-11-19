import { Suspense } from "react";
import { LoginForm } from "@/features/identity/login-form";

export const metadata = { title: "Đăng nhập · Gia Sư Cam" };

export default function LoginPage() {
  // Demo mode only (mục 3 mock login): the shared demo password is meant to be public on this page.
  return (
    <Suspense>
      <LoginForm demoPassword={process.env.DEMO_PASSWORD || null} />
    </Suspense>
  );
}

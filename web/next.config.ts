import type { NextConfig } from "next";
// Browser API calls go through the /bff route handler (adds the JWT from the httpOnly cookie); no rewrites needed.
const nextConfig: NextConfig = {
  output: "standalone",
};
export default nextConfig;

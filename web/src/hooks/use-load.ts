"use client";
import { useCallback, useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";

/** GETs path on mount (and when path changes); reload() refetches after a mutation. path=null skips. */
export function useLoad<T>(path: string | null) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [loading, setLoading] = useState(path !== null);

  const reload = useCallback(async () => {
    if (path === null) return;
    setLoading(true);
    try {
      setData(await api<T>(path));
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError(0, "UNKNOWN", "Có lỗi xảy ra"));
    } finally {
      setLoading(false);
    }
  }, [path]);

  useEffect(() => {
    void reload();
  }, [reload]);

  return { data, error, loading, reload };
}

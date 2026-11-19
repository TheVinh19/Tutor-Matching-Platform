"use client";
import { useLoad } from "@/hooks/use-load";
import type { Catalog, SubjectLevel } from "@/types/api";

/** Catalog + code-to-name helpers; falls back to the code while loading. */
export function useCatalog() {
  const { data } = useLoad<Catalog>("/v1/tutors/catalog");
  const find = (list: { code: string; name: string }[] | undefined, code: string) => list?.find((x) => x.code === code)?.name ?? code;
  return {
    catalog: data,
    subject: (code: string) => find(data?.subjects, code),
    level: (code: string) => find(data?.levels, code),
    area: (code: string) => {
      const a = data?.areas.find((x) => x.code === code);
      return a ? `${a.name}, ${a.province}` : code;
    },
    subjectLevel(s: SubjectLevel) {
      return `${find(data?.subjects, s.subjectCode)} ${find(data?.levels, s.levelCode)}`;
    },
  };
}

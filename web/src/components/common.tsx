"use client";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { ApiError, fmtDateTime } from "@/lib/api";
import { cn } from "@/lib/utils";

export function PageTitle({ title, description, children }: { title: string; description?: string; children?: React.ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
      </div>
      {children}
    </div>
  );
}

export function StatusBadge({ value }: { value: [string, "default" | "secondary" | "outline" | "destructive"] }) {
  return <Badge variant={value[1]}>{value[0]}</Badge>;
}

/** Shows the backend message; for schedule conflicts also lists the clashing sessions. */
export function ErrorBox({ error }: { error: ApiError | Error | null }) {
  if (!error) return null;
  const details = error instanceof ApiError ? error.details : undefined;
  const conflicts = error instanceof ApiError && error.code === "SCHEDULE_CONFLICT" && Array.isArray(details)
    ? (details as { startAt: string; side: string }[]) : [];
  const fields = error instanceof ApiError && error.code === "VALIDATION_FAILED" && Array.isArray(details)
    ? (details as { field: string; message: string }[]) : [];
  return (
    <div role="alert" className="rounded-lg border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive">
      {error.message}
      {conflicts.length > 0 && (
        <ul className="mt-1 list-disc pl-5">
          {conflicts.slice(0, 5).map((c, i) => (
            <li key={i}>{fmtDateTime(c.startAt)} — trùng lịch {c.side === "TUTOR" ? "gia sư (cần cách 60 phút)" : "Học sinh"}</li>
          ))}
        </ul>
      )}
      {fields.length > 0 && (
        <ul className="mt-1 list-disc pl-5">{fields.map((f) => <li key={f.field}>{f.field}: {f.message}</li>)}</ul>
      )}
    </div>
  );
}

export function Empty({ children }: { children: React.ReactNode }) {
  return <p className="rounded-lg border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">{children}</p>;
}

export const selectClass =
  "h-8 w-full rounded-lg border border-input bg-transparent px-2.5 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50";

export function NativeSelect({ className, ...props }: React.ComponentProps<"select">) {
  return <select className={cn(selectClass, className)} {...props} />;
}

/** Runs an async action and keeps its error/pending state. */
export function useAction() {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  async function run(fn: () => Promise<unknown>) {
    setPending(true);
    setError(null);
    try {
      await fn();
      return true;
    } catch (e) {
      setError(e as Error);
      return false;
    } finally {
      setPending(false);
    }
  }
  return { pending, error, run };
}

/** Button that first asks for a reason (reject, cancel report, dispute), then calls onSubmit(reason). */
export function ReasonAction({ label, placeholder, variant = "outline", onSubmit }: {
  label: string; placeholder: string; variant?: "outline" | "destructive"; onSubmit: (reason: string) => Promise<unknown>;
}) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const { pending, error, run } = useAction();
  if (!open) return <Button size="sm" variant={variant} onClick={() => setOpen(true)}>{label}</Button>;
  return (
    <div className="flex w-full flex-col gap-2">
      <Textarea aria-label={placeholder} placeholder={placeholder} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={1000} />
      <ErrorBox error={error} />
      <div className="flex gap-2">
        <Button size="sm" variant={variant} disabled={pending || !reason.trim()}
          onClick={async () => { if (await run(() => onSubmit(reason.trim()))) setOpen(false); }}>
          Gửi
        </Button>
        <Button size="sm" variant="ghost" onClick={() => setOpen(false)}>Hủy</Button>
      </div>
    </div>
  );
}

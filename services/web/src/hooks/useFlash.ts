import { useEffect, useRef, useState } from "react";

/** Returns "flash-up"/"flash-down" briefly after a numeric value changes (IBKR-style tick highlight). */
export function useFlash(value: number | null): string {
  const prev = useRef(value);
  const [cls, setCls] = useState("");
  useEffect(() => {
    const p = prev.current;
    prev.current = value;
    if (p == null || value == null || p === value) return;
    setCls(value > p ? "flash-up" : "flash-down");
    const t = setTimeout(() => setCls(""), 600);
    return () => clearTimeout(t);
  }, [value]);
  return cls;
}

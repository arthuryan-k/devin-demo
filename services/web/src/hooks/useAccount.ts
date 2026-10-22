import { useEffect, useState } from "react";
import type { Backend } from "../api/backend";
import type { AccountView } from "../api/types";

export function useAccount(backend: Backend): { view: AccountView | null; error: Error | null } {
  const [view, setView] = useState<AccountView | null>(null);
  const [error, setError] = useState<Error | null>(null);
  useEffect(
    () =>
      backend.subscribeAccount(
        (v) => {
          setView(v);
          setError(null);
        },
        setError,
      ),
    [backend],
  );
  return { view, error };
}

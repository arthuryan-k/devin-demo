import { useState, type FormEvent } from "react";
import { ApiError, type Backend } from "../api/backend";
import type { Session } from "../api/types";
import { DEMO_USER } from "../api/mock/fixtures";

export function LoginScreen({ backend, onSession }: { backend: Backend; onSession: (s: Session) => void }) {
  const [mode, setMode] = useState<"login" | "signup">("login");
  const demo = backend.kind === "mock";
  const [username, setUsername] = useState(demo ? DEMO_USER.username : "");
  const [password, setPassword] = useState(demo ? DEMO_USER.password : "");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const s = mode === "login" ? await backend.login(username, password) : await backend.signup(username, password);
      onSession(s);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Unable to reach the server");
      setBusy(false);
    }
  };

  return (
    <div className="login-screen">
      <form className="login-card" onSubmit={submit} aria-label={mode === "login" ? "Log in" : "Sign up"}>
        <div className="brand brand-lg">
          <span className="brand-mark" aria-hidden>
            ▲
          </span>
          <span>
            <b>DEVIN</b>DEMO
          </span>
        </div>
        <div className="login-sub">Trader Workstation</div>
        <div className="login-tabs" role="tablist">
          <button type="button" role="tab" aria-selected={mode === "login"} onClick={() => setMode("login")}>
            Log In
          </button>
          <button type="button" role="tab" aria-selected={mode === "signup"} onClick={() => setMode("signup")}>
            Open Account
          </button>
        </div>
        <label>
          Username
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required autoFocus />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={mode === "login" ? "current-password" : "new-password"}
            required
          />
        </label>
        {error && (
          <div className="form-error" role="alert">
            {error}
          </div>
        )}
        <button className="btn-primary" type="submit" disabled={busy}>
          {busy ? "Please wait…" : mode === "login" ? "Log In" : "Create Account"}
        </button>
        {demo && (
          <p className="login-note">
            Demo mode — simulated market data, no real orders. Use <code>{DEMO_USER.username}</code> /{" "}
            <code>{DEMO_USER.password}</code> or open a new paper account.
          </p>
        )}
      </form>
    </div>
  );
}

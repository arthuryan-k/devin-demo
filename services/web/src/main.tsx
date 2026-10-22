import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { createBackend } from "./api";
import "./styles.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App backend={createBackend()} />
  </StrictMode>,
);

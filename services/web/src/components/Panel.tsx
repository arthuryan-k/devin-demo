import type { ReactNode } from "react";

export function Panel(props: { title: ReactNode; actions?: ReactNode; className?: string; children: ReactNode; testId?: string }) {
  return (
    <section className={`panel ${props.className ?? ""}`} data-testid={props.testId}>
      <header className="panel-head">
        <h2>{props.title}</h2>
        {props.actions && <div className="panel-actions">{props.actions}</div>}
      </header>
      <div className="panel-body">{props.children}</div>
    </section>
  );
}

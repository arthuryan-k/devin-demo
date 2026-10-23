# eod-recon

End-of-day batch for the brokerage ledger, written in Perl 5 with a POSIX shell driver.

Each cycle `bin/eod_run.sh` extracts the portfolio ledger (`portfolio.accounts`, `positions`, `fills`, `marks`) and
the exchange tape (`trades_history`) from Postgres with `psql` into pipe-delimited `.dat` files, then
`bin/eod_recon.pl` produces, under `$EOD_SPOOL/<YYYYMMDD>/`:

- `EODRPT.<date>.txt` — statement of cash, reserved cash, market value and equity per account, per-symbol volume,
  and a list of reconciliation breaks (cash vs. starting cash and fills, reservations vs. holdings, positions vs.
  fills, settled vs. traded volume).
- `CLREXP.<date>.dat` — fixed-width (100-byte) clearing export: one `HDR`, one `DTL` per account/symbol with its net
  settled movement, holding and mark, and a `TRL` with record count and hash total.

Return codes: `0` clean, `4` breaks found, `8` input or output error.

## Run

In compose it runs as `eod-recon`, once at startup and then every `RECON_INTERVAL` seconds (default 3600):

```
docker compose logs -f eod-recon
docker compose exec eod-recon sh -c 'cat /var/spool/eod/*/EODRPT.*.txt'
docker compose run --rm -e RUN_ONCE=1 eod-recon     # one cycle, exits with the batch RC
```

Standalone against extracted files:

```
perl bin/eod_recon.pl <input dir> <output dir> [YYYYMMDD]
prove t/
```

| Env | Default |
| --- | --- |
| `DATABASE_URL` | `postgresql://postgres:postgres@postgres:5432/brokerage` |
| `EOD_SPOOL` | `/var/spool/eod` |
| `RECON_INTERVAL` | `3600` |
| `RUN_ONCE` | `0` |

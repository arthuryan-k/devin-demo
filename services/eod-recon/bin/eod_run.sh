#!/bin/sh
# eod_run.sh - extract the day's ledger from Postgres and run the EOD recon/clearing batch.
#
#   DATABASE_URL    postgres connection string
#   EOD_SPOOL       output root (reports land in $EOD_SPOOL/<YYYYMMDD>/)
#   RECON_INTERVAL  seconds between runs; RUN_ONCE=1 runs a single cycle and exits with the batch RC
#   BIZDATE         re-run a past business date (YYYYMMDD); defaults to today
#   EOD_POST_CMD    optional command run with the output directory once the batch succeeds

SPOOL=${EOD_SPOOL:-/var/spool/eod}
INTERVAL=${RECON_INTERVAL:-3600}
BIN=$(dirname "$0")
DBURL=${DATABASE_URL:-postgresql://postgres:postgres@postgres:5432/brokerage}

extract() {
    out=$1
    asof=$2
    mkdir -p "$out" || return 8
    # one REPEATABLE READ transaction so the ledger and the tape are a consistent snapshot
    psql "$DBURL" -X -q -v ON_ERROR_STOP=1 -At -F'|' <<SQL || return 8
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
\o $out/accounts.dat
SELECT account_id, cash, reserved_cash, starting_cash FROM portfolio.accounts ORDER BY account_id;
\o $out/positions.dat
SELECT account_id, symbol, shares, reserved_shares, cost_basis, realized_pnl FROM portfolio.positions
 ORDER BY account_id, symbol;
\o $out/fills.dat
SELECT fill_id, account_id, symbol, order_id, side, price, qty FROM portfolio.fills ORDER BY fill_id;
\o $out/trades.dat
SELECT symbol, seq, event_index, coalesce(maker_participant_id, 0), coalesce(taker_participant_id, 0),
       price_ticks, qty FROM trades_history
 WHERE event_time::date <= to_date('$asof', 'YYYYMMDD') ORDER BY symbol, seq, event_index;
\o $out/marks.dat
SELECT symbol, last_price FROM portfolio.marks ORDER BY symbol;
\o
COMMIT;
SQL
    return 0
}

cycle() {
    bizdate=${BIZDATE:-$(date -u +%Y%m%d)}
    dir=$SPOOL/$bizdate
    echo "EOD0001I $(date -u +%H:%M:%S) START BUSINESS DATE $bizdate"
    extract "$dir/in" "$bizdate"
    rc=$?
    if [ $rc -ne 0 ]; then
        echo "EOD0099E EXTRACT FAILED RC=$rc"
        return $rc
    fi
    perl "$BIN/eod_recon.pl" "$dir/in" "$dir" "$bizdate"
    rc=$?
    if [ $rc -lt 8 ] && [ -n "$EOD_POST_CMD" ]; then
        eval "$EOD_POST_CMD $dir"
    fi
    echo "EOD0002I $(date -u +%H:%M:%S) END BUSINESS DATE $bizdate RC=$rc"
    return $rc
}

if [ "${RUN_ONCE:-0}" = "1" ]; then
    cycle
    exit $?
fi

while true; do
    cycle
    sleep "$INTERVAL"
done

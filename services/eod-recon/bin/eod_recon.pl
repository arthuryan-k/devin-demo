#!/usr/bin/perl
#
# eod_recon.pl - end of day ledger reconciliation and clearing export
#
#   usage: eod_recon.pl <input dir> <output dir> [YYYYMMDD]
#
# Input (pipe delimited, no header, as written by eod_run.sh):
#   accounts.dat   account_id|cash|reserved_cash|starting_cash
#   positions.dat  account_id|symbol|shares|reserved_shares|cost_basis|realized_pnl
#   fills.dat      fill_id|account_id|symbol|order_id|side|price|qty
#   trades.dat     symbol|seq|event_index|maker_pid|taker_pid|price|qty
#   marks.dat      symbol|last_price
#
# Output:
#   EODRPT.<date>.txt    statement of positions and reconciliation report
#   CLREXP.<date>.dat    fixed width clearing file (HDR / DTL / TRL)
#
# Return codes: 0 clean, 4 breaks found, 8 bad input / cannot write output.
#
use strict;
use warnings;

my $VERSION = '1.7';

my ($INDIR, $OUTDIR, $BIZDATE) = @ARGV;
if (!defined $INDIR || !defined $OUTDIR) {
    print STDERR "usage: $0 <input dir> <output dir> [YYYYMMDD]\n";
    exit 8;
}
if (!defined $BIZDATE) {
    my @t = gmtime(time);
    $BIZDATE = sprintf("%04d%02d%02d", $t[5] + 1900, $t[4] + 1, $t[3]);
}

my (%ACCT, %POS, %MARK, %FILLS, %TRADES, @BREAKS);
my $RC = 0;

sub read_file {
    my ($name, $ncols) = @_;
    my @rows;
    my $path = "$INDIR/$name";
    open(my $fh, '<', $path) or do { print STDERR "EOD0010E CANNOT OPEN $path: $!\n"; exit 8; };
    while (my $line = <$fh>) {
        chomp $line;
        $line =~ s/\r$//;
        next if $line eq '';
        my @f = split /\|/, $line, -1;
        if (scalar(@f) != $ncols) {
            print STDERR "EOD0011E $name LINE $.: EXPECTED $ncols FIELDS, GOT " . scalar(@f) . "\n";
            exit 8;
        }
        push @rows, \@f;
    }
    close $fh;
    return @rows;
}

sub money {
    my $ticks = shift;
    my $sign = $ticks < 0 ? '-' : '';
    $ticks = -$ticks if $ticks < 0;
    my $s = sprintf("%d.%02d", int($ticks / 100), $ticks % 100);
    1 while $s =~ s/^(\d+)(\d{3})/$1,$2/;
    return $sign . $s;
}

sub brk {
    my ($kind, $key, $msg) = @_;
    push @BREAKS, sprintf("%-8s %-16s %s", $kind, $key, $msg);
    $RC = 4 if $RC < 4;
}

# ------------------------------------------------------------------------------------------------
# main
# ------------------------------------------------------------------------------------------------
sub process_day {
    my @accounts = read_file('accounts.dat', 4);
    my @positions = read_file('positions.dat', 6);
    my @fills = read_file('fills.dat', 7);
    my @trades = read_file('trades.dat', 7);
    my @marks = read_file('marks.dat', 2);

    for my $r (@accounts) {
        $ACCT{$r->[0]} = { cash => $r->[1], reserved => $r->[2], start => $r->[3], buys => 0, sells => 0,
                           nfills => 0, mv => 0 };
    }
    for my $r (@marks) {
        $MARK{$r->[0]} = $r->[1];
    }
    for my $r (@positions) {
        my ($acct, $sym, $sh, $rsh, $cost, $pnl) = @$r;
        $POS{$acct}{$sym} = { shares => $sh, reserved => $rsh, cost => $cost, pnl => $pnl, bought => 0, sold => 0 };
        if (!exists $ACCT{$acct}) {
            brk('ORPHAN', "$acct/$sym", "position without an account");
        }
    }

    # fills -> cash and share movements
    for my $r (@fills) {
        my ($id, $acct, $sym, $oid, $side, $px, $qty) = @$r;
        my $value = $px * $qty;
        if (!exists $ACCT{$acct}) {
            brk('ORPHAN', "fill $id", "fill for unknown account $acct");
            $FILLS{$sym}{$side} += $qty if $side eq 'BUY' || $side eq 'SELL';
            next;
        }
        $ACCT{$acct}{nfills}++;
        if ($side eq 'BUY') {
            $ACCT{$acct}{buys} += $value;
            $POS{$acct}{$sym}{bought} += $qty;
            $FILLS{$sym}{BUY} += $qty;
        } elsif ($side eq 'SELL') {
            $ACCT{$acct}{sells} += $value;
            $POS{$acct}{$sym}{sold} += $qty;
            $FILLS{$sym}{SELL} += $qty;
        } else {
            brk('BADSIDE', "fill $id", "side '$side'");
        }
        $FILLS{$sym}{n}++;
    }

    for my $r (@trades) {
        my ($sym, $seq, $idx, $mpid, $tpid, $px, $qty) = @$r;
        $TRADES{$sym}{qty} += $qty;
        $TRADES{$sym}{n}++;
        $TRADES{$sym}{notional} += $px * $qty;
        # TODO: per participant match once history-writer stores order ids against portfolio accounts
    }

    # cash recon: starting cash - buys + sells must equal ledger cash
    for my $acct (sort { $a <=> $b } keys %ACCT) {
        my $a = $ACCT{$acct};
        my $expect = $a->{start} - $a->{buys} + $a->{sells};
        if ($expect != $a->{cash}) {
            brk('CASH', $acct, sprintf("ledger %s expected %s diff %s", money($a->{cash}), money($expect),
                money($a->{cash} - $expect)));
        }
        if ($a->{reserved} > $a->{cash}) {
            brk('RESV', $acct, "reserved cash " . money($a->{reserved}) . " exceeds cash " . money($a->{cash}));
        }
    }

    # position recon: shares less net fills is the opening grant and can never be negative
    for my $acct (sort { $a <=> $b } keys %POS) {
        for my $sym (sort keys %{ $POS{$acct} }) {
            my $p = $POS{$acct}{$sym};
            next if !defined $p->{shares};
            my $opening = $p->{shares} - $p->{bought} + $p->{sold};
            if ($opening < 0) {
                brk('SHARES', "$acct/$sym", "holds $p->{shares} but fills net to " . ($p->{bought} - $p->{sold}));
            }
            if ($p->{reserved} > $p->{shares}) {
                brk('RESV', "$acct/$sym", "reserved $p->{reserved} of $p->{shares} shares");
            }
            if ($p->{shares} > 0) {
                if (exists $MARK{$sym}) {
                    $ACCT{$acct}{mv} += $p->{shares} * $MARK{$sym} if exists $ACCT{$acct};
                } else {
                    brk('NOMARK', "$acct/$sym", "no mark, valued at cost");
                    $ACCT{$acct}{mv} += $p->{cost} if exists $ACCT{$acct};
                }
            }
        }
    }

    # volume check: each side of the street can only have settled what actually traded
    my %syms = map { $_ => 1 } (keys %TRADES, keys %FILLS);
    for my $sym (sort keys %syms) {
        my $traded = $TRADES{$sym}{qty} || 0;
        for my $side ('BUY', 'SELL') {
            my $filled = $FILLS{$sym}{$side} || 0;
            if ($filled > $traded) {
                brk('VOLUME', "$sym $side", "settled $filled but only $traded traded");
            }
        }
    }

    # ---- report ----
    my $rpt = "$OUTDIR/EODRPT.$BIZDATE.txt";
    open(my $out, '>', $rpt) or do { print STDERR "EOD0020E CANNOT WRITE $rpt: $!\n"; exit 8; };
    my $line = '-' x 100;
    printf $out "%-60s%40s\n", "DEVIN-DEMO BROKERAGE  END OF DAY LEDGER RECONCILIATION", "BUSINESS DATE $BIZDATE";
    printf $out "%-60s%40s\n", "PROGRAM EODRECON V$VERSION", "RUN " . scalar(gmtime(time)) . " UTC";
    print $out "$line\n";
    printf $out "%-10s %18s %18s %18s %18s %6s\n", "ACCOUNT", "CASH", "RESERVED", "MKT VALUE", "EQUITY", "FILLS";
    print $out "$line\n";
    my ($tcash, $tres, $tmv) = (0, 0, 0);
    for my $acct (sort { $a <=> $b } keys %ACCT) {
        my $a = $ACCT{$acct};
        printf $out "%-10s %18s %18s %18s %18s %6d\n", $acct, money($a->{cash}), money($a->{reserved}),
            money($a->{mv}), money($a->{cash} + $a->{mv}), $a->{nfills};
        $tcash += $a->{cash};
        $tres += $a->{reserved};
        $tmv += $a->{mv};
    }
    print $out "$line\n";
    printf $out "%-10s %18s %18s %18s %18s\n", "TOTAL", money($tcash), money($tres), money($tmv), money($tcash + $tmv);
    print $out "\n";
    printf $out "%-8s %12s %12s %12s %20s\n", "SYMBOL", "TRADES", "VOLUME", "MARK", "NOTIONAL";
    for my $sym (sort keys %syms) {
        printf $out "%-8s %12d %12d %12s %20s\n", $sym, $TRADES{$sym}{n} || 0, $TRADES{$sym}{qty} || 0,
            exists $MARK{$sym} ? money($MARK{$sym}) : 'N/A', money($TRADES{$sym}{notional} || 0);
    }
    print $out "\n";
    if (@BREAKS) {
        printf $out "*** %d RECONCILIATION BREAK(S) ***\n", scalar(@BREAKS);
        print $out "$_\n" for @BREAKS;
    } else {
        print $out "*** NO BREAKS ***\n";
    }
    printf $out "END OF REPORT RC=%02d\n", $RC;
    close $out;

    # ---- clearing export: one DTL per account/symbol net settled movement ----
    my $clr = "$OUTDIR/CLREXP.$BIZDATE.dat";
    open(my $cx, '>', $clr) or do { print STDERR "EOD0021E CANNOT WRITE $clr: $!\n"; exit 8; };
    printf $cx "HDR%-8s%-8s%-10s%71s\n", 'DEVINBRK', $BIZDATE, 'EODCLR', '';
    my ($ndtl, $hash) = (0, 0);
    for my $acct (sort { $a <=> $b } keys %POS) {
        for my $sym (sort keys %{ $POS{$acct} }) {
            my $p = $POS{$acct}{$sym};
            my $net = ($p->{bought} || 0) - ($p->{sold} || 0);
            next if $net == 0 && !$p->{shares};
            printf $cx "DTL%010d%-10s%s%015d%015d%018d%28s\n", $acct, $sym, $net < 0 ? 'S' : 'B', abs($net),
                $p->{shares} || 0, $MARK{$sym} || 0, '';
            $ndtl++;
            $hash += $acct;
        }
    }
    printf $cx "TRL%09d%018d%70s\n", $ndtl, $hash, '';
    close $cx;

    print "EOD0003I ACCOUNTS=" . scalar(keys %ACCT) . " FILLS=" . scalar(@fills) . " TRADES=" . scalar(@trades)
        . " BREAKS=" . scalar(@BREAKS) . "\n";
    print "EOD0004I REPORT $rpt\n";
    print "EOD0005I CLEARING $clr ($ndtl DETAIL RECORDS)\n";
}

process_day();
exit $RC;

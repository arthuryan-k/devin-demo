use strict;
use warnings;
use File::Basename qw(dirname);
use File::Temp qw(tempdir);
use Test::More;

my $here = dirname(__FILE__);
my $prog = "$here/../bin/eod_recon.pl";

sub run_batch {
    my ($fixture) = @_;
    my $out = tempdir(CLEANUP => 1);
    my $log = `perl $prog $here/fixtures/$fixture $out 20261023 2>&1`;
    my $rc = $? >> 8;
    open(my $fh, '<', "$out/EODRPT.20261023.txt") or return ($rc, $log, '', []);
    my $rpt = do { local $/; <$fh> };
    close $fh;
    open(my $cx, '<', "$out/CLREXP.20261023.dat") or return ($rc, $log, $rpt, []);
    my @clr = <$cx>;
    close $cx;
    return ($rc, $log, $rpt, \@clr);
}

my ($rc, $log, $rpt, $clr) = run_batch('clean');
is($rc, 0, 'clean books exit 0');
like($log, qr/ACCOUNTS=2 FILLS=2 TRADES=1 BREAKS=0/, 'summary line');
like($rpt, qr/NO BREAKS/, 'no breaks reported');
like($rpt, qr/^1001\s+990\.00\s+0\.00\s+10\.50\s+1,000\.50\s+1$/m, 'account 1001 valued at mark');
like($rpt, qr/^ACME\s+1\s+10\s+1\.05\s+10\.00$/m, 'symbol volume line');
is(scalar(@$clr), 5, 'header, three details, trailer');
like($clr->[0], qr/^HDRDEVINBRK20261023EODCLR/, 'clearing header');
like($clr->[1], qr/^DTL0000001001ACME      B0{13}10/, 'buyer detail');
like($clr->[$#$clr], qr/^TRL0{8}3/, 'trailer record count');
is(length($_), 101, 'fixed width record') for @$clr;  # 100 + newline

($rc, $log, $rpt) = run_batch('breaks');
is($rc, 4, 'breaks exit 4');
like($rpt, qr/^CASH\s+1001\s+ledger 995\.00 expected 990\.00 diff 5\.00$/m, 'cash break');
like($rpt, qr/^ORPHAN\s+fill 3/m, 'orphan fill');
like($rpt, qr/^VOLUME\s+ACME BUY\s+settled 11 but only 10 traded$/m, 'volume break');

my $out = tempdir(CLEANUP => 1);
system("perl $prog $here/does-not-exist $out 20261023 2>/dev/null");
is($? >> 8, 8, 'missing input exits 8');

done_testing();

# EOD reconciliation / clearing export batch. Perl 5 + psql; no CPAN modules.
FROM postgres:16-alpine
RUN apk add --no-cache perl
COPY bin/ /opt/eod/bin/
RUN chmod +x /opt/eod/bin/*.sh /opt/eod/bin/*.pl && mkdir -p /var/spool/eod
ENV EOD_SPOOL=/var/spool/eod \
    RECON_INTERVAL=3600
VOLUME /var/spool/eod
ENTRYPOINT ["/opt/eod/bin/eod_run.sh"]

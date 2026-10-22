import logging

import uvicorn

from . import config
from .app import create_app
from .hub import FeedHub
from .source import KafkaSource


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    cfg = config.load()
    hub = FeedHub(cfg.symbols, cfg.max_backlog)
    source = KafkaSource(hub, cfg.brokers) if cfg.brokers else None
    if source is None:
        logging.getLogger(__name__).warning("KAFKA_BROKERS not set: serving empty books")
    uvicorn.run(create_app(hub, source), host=cfg.host, port=cfg.port, log_level="info")


if __name__ == "__main__":
    main()

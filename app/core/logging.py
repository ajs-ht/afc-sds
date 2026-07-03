import logging
import sys


def configure_logging(log_level: str) -> None:
    root = logging.getLogger()
    root.setLevel(log_level.upper())

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(
        logging.Formatter(
            fmt="%(asctime)s %(levelname)s %(name)s: %(message)s",
            datefmt="%Y-%m-%dT%H:%M:%S%z",
        )
    )
    root.handlers = [handler]


usage_logger = logging.getLogger("afc_sds.usage")


def log_usage(
    *,
    request_id: str,
    model: str,
    stop_reason: str | None,
    input_tokens: int,
    output_tokens: int,
    cache_creation_input_tokens: int,
    cache_read_input_tokens: int,
) -> None:
    usage_logger.info(
        "sds_extraction request_id=%s model=%s stop_reason=%s "
        "input_tokens=%d output_tokens=%d cache_creation_input_tokens=%d "
        "cache_read_input_tokens=%d",
        request_id,
        model,
        stop_reason,
        input_tokens,
        output_tokens,
        cache_creation_input_tokens,
        cache_read_input_tokens,
    )

import json
import logging
import sys


class JsonFormatter(logging.Formatter):
    """Single-line JSON log records for machine-readable log pipelines."""

    def format(self, record: logging.LogRecord) -> str:
        entry = {
            "time": self.formatTime(record, datefmt="%Y-%m-%dT%H:%M:%S%z"),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        if record.exc_info:
            entry["exc_info"] = self.formatException(record.exc_info)
        return json.dumps(entry, ensure_ascii=False)


def configure_logging(log_level: str, log_format: str = "text") -> None:
    root = logging.getLogger()
    root.setLevel(log_level.upper())

    if log_format == "json":
        formatter: logging.Formatter = JsonFormatter()
    else:
        formatter = logging.Formatter(
            fmt="%(asctime)s %(levelname)s %(name)s: %(message)s",
            datefmt="%Y-%m-%dT%H:%M:%S%z",
        )

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(formatter)
    root.handlers = [handler]


usage_logger = logging.getLogger("afc_sds.usage")
access_logger = logging.getLogger("afc_sds.access")


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


def log_access(
    *,
    request_id: str,
    method: str,
    path: str,
    status_code: int,
    duration_ms: float,
) -> None:
    access_logger.info(
        "request request_id=%s method=%s path=%s status=%d duration_ms=%.1f",
        request_id,
        method,
        path,
        status_code,
        duration_ms,
    )

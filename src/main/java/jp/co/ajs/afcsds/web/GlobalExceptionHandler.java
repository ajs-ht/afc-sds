package jp.co.ajs.afcsds.web;

import jakarta.servlet.http.HttpServletRequest;
import jp.co.ajs.afcsds.core.AppException;
import jp.co.ajs.afcsds.schema.Responses.ErrorDetail;
import jp.co.ajs.afcsds.schema.Responses.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Renders every error as the shared JSON body
 * {@code {"error": {"type", "message", "request_id"}}} — routes never need
 * try/catch. When adding an error type, also update the error table in the
 * README.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger("afc_sds");

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleAppException(
            AppException exc, HttpServletRequest request) {
        String requestId = RequestIdFilter.requestId(request);

        if (exc.statusCode() >= 500) {
            logger.error(
                    "{}: {} ({}) request_id={}",
                    exc.errorType(),
                    exc.getMessage(),
                    exc.details(),
                    requestId);
        } else {
            logger.info(
                    "{}: {} ({}) request_id={}",
                    exc.errorType(),
                    exc.getMessage(),
                    exc.details(),
                    requestId);
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.status(exc.statusCode());
        // A retryable rejection (e.g. server_busy) tells callers when to come
        // back via the standard Retry-After header.
        Object retryAfter = exc.details().get("retry_after_seconds");
        if (retryAfter != null) {
            response.header("Retry-After", retryAfter.toString());
        }
        return response.body(
                new ErrorResponse(new ErrorDetail(exc.errorType(), exc.getMessage(), requestId)));
    }

    /**
     * The servlet-level multipart cap (a slack above MAX_UPLOAD_MB, see
     * WebConfig) tripped before our own size check could produce the
     * canonical message — same error type either way.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUpload(
            MaxUploadSizeExceededException exc, HttpServletRequest request) {
        String requestId = RequestIdFilter.requestId(request);
        logger.info("file_too_large: {} request_id={}", exc.getMessage(), requestId);
        return ResponseEntity.status(400)
                .body(
                        new ErrorResponse(
                                new ErrorDetail(
                                        "file_too_large",
                                        "Request body exceeds the upload size limit.",
                                        requestId)));
    }

    /** Request-shape errors (e.g. missing `file` part) → 422 validation_error. */
    @ExceptionHandler({
        MissingServletRequestPartException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        MultipartException.class
    })
    public ResponseEntity<ErrorResponse> handleValidation(
            Exception exc, HttpServletRequest request) {
        String requestId = RequestIdFilter.requestId(request);
        logger.info("validation_error: {} request_id={}", exc.getMessage(), requestId);
        return ResponseEntity.status(422)
                .body(
                        new ErrorResponse(
                                new ErrorDetail(
                                        "validation_error", "Request validation failed.", requestId)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            NoResourceFoundException exc, HttpServletRequest request) {
        String requestId = RequestIdFilter.requestId(request);
        return ResponseEntity.status(404)
                .body(new ErrorResponse(new ErrorDetail("not_found", "Not Found", requestId)));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exc, HttpServletRequest request) {
        String requestId = RequestIdFilter.requestId(request);
        return ResponseEntity.status(405)
                .body(
                        new ErrorResponse(
                                new ErrorDetail("method_not_allowed", "Method Not Allowed", requestId)));
    }

    /** Catch anything that isn't already an AppException. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exc, HttpServletRequest request) {
        String requestId = RequestIdFilter.requestId(request);
        logger.error("internal_error: {} request_id={}", exc.getMessage(), requestId, exc);
        return ResponseEntity.status(500)
                .body(
                        new ErrorResponse(
                                new ErrorDetail(
                                        "internal_error", "An unexpected error occurred.", requestId)));
    }
}

package jp.co.ajs.afcsds.web;

import static org.assertj.core.api.Assertions.assertThat;

import jp.co.ajs.afcsds.schema.Responses.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Direct handler tests for paths MockMvc can't reach: the servlet-level
 * multipart cap (a slack above MAX_UPLOAD_MB, see WebConfig) is enforced by
 * the real multipart parser, which MockMvc bypasses.
 */
class GlobalExceptionHandlerTest {

    private static MockHttpServletRequest requestWithId(String requestId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, requestId);
        return request;
    }

    @Test
    void servletMultipartCapMapsToFileTooLarge() {
        ResponseEntity<ErrorResponse> response =
                new GlobalExceptionHandler()
                        .handleMaxUpload(
                                new MaxUploadSizeExceededException(40 * 1024 * 1024),
                                requestWithId("req-cap"));

        // Same error type as the application-level size check, so callers see
        // one contract regardless of which limit tripped first.
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().error().type()).isEqualTo("file_too_large");
        assertThat(response.getBody().error().requestId()).isEqualTo("req-cap");
    }
}

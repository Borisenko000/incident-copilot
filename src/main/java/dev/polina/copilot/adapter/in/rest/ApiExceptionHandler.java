package dev.polina.copilot.adapter.in.rest;

import static java.util.stream.Collectors.joining;

import dev.polina.copilot.adapter.in.rest.PayloadSizeLimitFilter.PayloadTooLargeException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Ошибки API в формате ProblemDetail (README §5.4). Базовый класс Spring уже отвечает
 * ProblemDetail на стандартные ошибки (400 на невалидный JSON и не-UUID в пути, 404 и т. д.);
 * здесь переопределены только отличия: правила полей → 422, слишком большое тело → 413,
 * непредвиденная ошибка → 500 без деталей.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(joining("; "));
        HttpStatus unprocessable = HttpStatus.valueOf(422);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(unprocessable, detail);
        problem.setTitle("Invalid request fields");
        return handleExceptionInternal(ex, problem, headers, unprocessable, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        PayloadTooLargeException tooLarge = findCause(ex, PayloadTooLargeException.class);
        if (tooLarge != null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, tooLarge.getMessage());
            problem.setTitle("Payload too large");
            return handleExceptionInternal(ex, problem, headers, HttpStatus.CONTENT_TOO_LARGE, request);
        }
        return super.handleHttpMessageNotReadable(ex, headers, status, request);
    }

    /** Всё непредвиденное → 500 без подробностей наружу (README §5.4); причина — только в лог. */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        logger.error("Unhandled API error", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
        problem.setTitle("Internal error");
        return problem;
    }

    private static <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }
}

package dev.polina.copilot.adapter.in.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ограничивает размер тела любого запроса: при чтении сверх лимита бросает
 * {@link PayloadTooLargeException}, которую {@link ApiExceptionHandler} превращает в 413.
 * Считаются реально прочитанные байты, поэтому лимит работает и без заголовка Content-Length.
 * Путь не проверяется намеренно: сырой URI (/api;x=1/..., %61pi) отличается от пути,
 * по которому Spring MVC выбирает контроллер, и проверка по префиксу обходилась бы.
 */
@Component
class PayloadSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    PayloadSizeLimitFilter(@Value("${copilot.ingestion.max-payload-size}") DataSize maxPayloadSize) {
        this.maxBytes = maxPayloadSize.toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    /** Тело больше лимита. IOException — чтобы Jackson обернул её, а Spring выдал HttpMessageNotReadableException. */
    static final class PayloadTooLargeException extends IOException {
        PayloadTooLargeException(long maxBytes) {
            super("request body exceeds " + maxBytes + " bytes");
        }
    }

    /** Один счётчик на запрос: и getInputStream(), и getReader() читают через один LimitedInputStream. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long maxBytes;
        private LimitedInputStream stream;
        private BufferedReader reader;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read;

        LimitedInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = delegate.read(buffer, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws PayloadTooLargeException {
            read += n;
            if (read > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}

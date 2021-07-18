package co.elastic.apm.agent.servlet.helper;

import javax.servlet.http.HttpServletRequest;
import java.util.Enumeration;

public class JavaxServletRequestHeaderGetter extends CommonServletRequestHeaderGetter<HttpServletRequest> {

    private static final JavaxServletRequestHeaderGetter INSTANCE = new JavaxServletRequestHeaderGetter();

    static CommonServletRequestHeaderGetter getInstance() {
        return INSTANCE;
    }

    @Override
    String getHeader(String headerName, HttpServletRequest carrier) {
        return carrier.getHeader(headerName);
    }

    @Override
    Enumeration<String> getHeaders(String headerName, HttpServletRequest carrier) {
        return carrier.getHeaders(headerName);
    }
}
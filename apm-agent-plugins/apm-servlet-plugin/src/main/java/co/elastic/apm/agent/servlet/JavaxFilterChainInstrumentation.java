package co.elastic.apm.agent.servlet;

public class JavaxFilterChainInstrumentation extends CommonFilterChainInstrumentation {
    @Override
    String filterChainTypeMatcherClassName() {
        return "javax.servlet.FilterChain";
    }

    @Override
    String[] filterChainMethodMatcherArgumentClassNames() {
        return new String[]{"javax.servlet.ServletRequest", "javax.servlet.ServletResponse"};
    }

    @Override
    public String getAdviceClassName() {
        return "co.elastic.apm.agent.servlet.JavaxServletApiAdvice";
    }
}

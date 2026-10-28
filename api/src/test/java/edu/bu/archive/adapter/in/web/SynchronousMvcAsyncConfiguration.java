package edu.bu.archive.adapter.in.web;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * TEST-ONLY: runs Spring MVC's async work inline instead of on a thread pool.
 *
 * <p>Why this exists. The attachment download endpoints return a
 * {@code StreamingResponseBody}, so Spring starts async processing and hands
 * the body-writing callable to the MVC async task executor. In a real servlet
 * container that is fine. Under MockMvc it is not: {@code mockMvc.perform(...)}
 * returns on the test thread while the worker is still writing, and
 * {@code HeaderWriterFilter.doHeadersAfter} then adds the static security
 * headers as the filter chain unwinds - on the test thread - while the worker
 * is committing the same response. Both end up in
 * {@code MockHttpServletResponse}'s {@code LinkedCaseInsensitiveMap}, which is
 * a plain {@code HashMap} underneath and is not thread-safe, and
 * {@code computeIfAbsent} throws {@link java.util.ConcurrentModificationException}
 * when it sees the modCount move.
 *
 * <p>The race is inside {@code perform(...)}, which is why calling
 * {@code getAsyncResult()} afterwards does not prevent it - that fences the
 * thread only after the damage can already have been done. Running the
 * executor synchronously removes the second thread entirely, so exactly one
 * thread ever touches the response.
 *
 * <p>This changes no production behaviour: it is a test-context bean, and the
 * deployed application keeps its real async executor.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SynchronousMvcAsyncConfiguration implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(new TaskExecutorAdapter(Runnable::run));
    }
}

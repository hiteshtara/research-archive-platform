package edu.bu.archive.adapter.in.web;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/*
 * Test-only: runs StreamingResponseBody callables on the request thread.
 *
 * Under MockMvc a streaming endpoint's body is written by an async worker
 * while the request thread is still unwinding the Spring Security filter
 * chain. HeaderWriterFilter.doHeadersAfter on the request thread and the
 * worker's commit (HeaderWriterResponse.onResponseCommitted) can then both
 * write headers into the same MockHttpServletResponse, whose header map is
 * not thread-safe - an intermittent ConcurrentModificationException
 * (2026-10-01: 5 in 100 isolated runs of the Award download security test).
 * 463dad5 removed the second asyncDispatch, which halved the exposure but
 * left this race. Running the callable synchronously makes the body and
 * its commit finish before the filter chain unwinds, so there is only one
 * writer. It changes nothing outside these tests.
 */
@TestConfiguration
@Order(Ordered.LOWEST_PRECEDENCE)
class SynchronousStreamingTestConfiguration implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(new TaskExecutorAdapter(new SyncTaskExecutor()));
    }
}

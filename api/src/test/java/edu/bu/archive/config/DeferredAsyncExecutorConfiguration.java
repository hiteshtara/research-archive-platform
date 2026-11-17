package edu.bu.archive.config;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * Makes Spring MVC async (the {@code StreamingResponseBody} download and
 * report endpoints) run on the TEST thread, after MockMvc has finished with
 * the request, instead of on a background thread racing it.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code MockHttpServletResponse} is not thread-safe. With the default
 * executor, a streaming body starts on a background thread while the test
 * thread is still inside the filter chain and MockMvc's result printer.
 * Both write or read the same mock header map: Spring Security's
 * {@code HeaderWriterFilter} sets headers from either thread (whichever
 * commits the response first), and the printer streams over the header
 * values. In a full DB-list run this surfaced intermittently as
 * {@code ConcurrentModificationException} in
 * {@code LinkedCaseInsensitiveMap.computeIfAbsent} and
 * {@code IllegalStateException: End size 1 is less than fixed size 2} in
 * {@code HeaderValueHolder.getStringValues} - both inside the mock response,
 * neither in archive code.
 *
 * <p>Registered under Spring Boot's {@code applicationTaskExecutor} name, the
 * executor MVC async uses, so Boot's own pool backs off. Tasks are queued on
 * submit and run only when the test calls {@link DeferredAsyncExecutor#runPending()},
 * which makes every request strictly sequential: dispatch, then body, then
 * async dispatch.
 */
@TestConfiguration(proxyBeanMethods = false)
class DeferredAsyncExecutorConfiguration {

    @Bean(name = "applicationTaskExecutor")
    DeferredAsyncExecutor applicationTaskExecutor() {
        return new DeferredAsyncExecutor();
    }

    /** Queues every task; runs them on the caller's thread when asked. */
    static final class DeferredAsyncExecutor implements AsyncTaskExecutor {

        private final Deque<Runnable> pending = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable task) {
            pending.add(task);
        }

        @Override
        public Future<?> submit(Runnable task) {
            FutureTask<Object> future = new FutureTask<>(task, null);
            execute(future);
            return future;
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            FutureTask<T> future = new FutureTask<>(task);
            execute(future);
            return future;
        }

        /** Runs everything queued so far, in order, on the calling thread. */
        void runPending() {
            Runnable next;
            while ((next = poll()) != null) {
                next.run();
            }
        }

        private synchronized Runnable poll() {
            return pending.poll();
        }
    }
}

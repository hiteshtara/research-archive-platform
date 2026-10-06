package edu.bu.archive.config;

import edu.bu.archive.adapter.in.web.RequestParameterValidationInterceptor;
import edu.bu.archive.adapter.in.web.SearchRequestTimingInterceptor;
import edu.bu.archive.application.service.SearchTimingLog;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/*
 * Registers the request-parameter character check across the whole API
 * surface, not only the five search endpoints that exposed it (QA
 * TC-017): the same NUL byte in any other parameter - a sponsor filter,
 * a document number - would reach PostgreSQL the same way.
 *
 * Separate from WebCorsConfiguration (the other WebMvcConfigurer) so
 * each one carries a single concern.
 */
@Configuration
public class WebRequestValidationConfiguration implements WebMvcConfigurer {

    private final SearchTimingLog timingLog;

    /*
     * ObjectProvider, not a hard dependency: the TC-042 timing bean is
     * application-layer, so a @WebMvcTest slice importing this
     * configuration for the parameter check alone has no reason to
     * supply it. Absent means disabled, which is what a controller
     * slice wants anyway.
     */
    public WebRequestValidationConfiguration(
            ObjectProvider<SearchTimingLog> timingLogProvider
    ) {
        SearchTimingLog provided = timingLogProvider.getIfAvailable();
        this.timingLog = provided != null ? provided : new SearchTimingLog(false);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry
                .addInterceptor(new RequestParameterValidationInterceptor())
                .addPathPatterns("/api/**");

        /*
         * TEMPORARY, QA TC-042. Registered second so the parameter check
         * above still runs first. Its preHandle/afterCompletion window
         * spans parameter validation, orchestration and response
         * writing, which is what makes REQUEST_TOTAL comparable with the
         * stages inside it.
         *
         * Scoped to Global Search: that is the slow surface under
         * investigation, and a timing line on every archive request
         * would be noise. Does nothing at all unless
         * app.search.timing.enabled is true.
         */
        registry
                .addInterceptor(new SearchRequestTimingInterceptor(timingLog))
                .addPathPatterns("/api/global-search");
    }
}

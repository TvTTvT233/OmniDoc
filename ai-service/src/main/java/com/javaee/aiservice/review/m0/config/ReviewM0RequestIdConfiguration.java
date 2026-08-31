package com.javaee.aiservice.review.m0.config;

import com.javaee.aiservice.review.m0.api.ReviewM0RequestIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class ReviewM0RequestIdConfiguration {

    @Bean
    FilterRegistrationBean<ReviewM0RequestIdFilter> reviewM0RequestIdFilter() {
        FilterRegistrationBean<ReviewM0RequestIdFilter> registration =
                new FilterRegistrationBean<>(new ReviewM0RequestIdFilter());
        registration.addUrlPatterns("/api/review-m0/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}

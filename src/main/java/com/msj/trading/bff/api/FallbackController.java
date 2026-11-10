package com.msj.trading.bff.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Target of the circuit breaker {@code forward:} when a downstream service times out, fails or is cut off.
 * Internal only: reached by a servlet forward, never meant to be called by the browser.
 * No method restriction: the forward keeps the original HTTP method (GET, POST...).
 */
@RestController
class FallbackController {

    @RequestMapping("/fallback/{service}")
    ProblemDetail fallback(@PathVariable String service) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "The service is temporarily unavailable, please retry shortly");
        problem.setTitle("Service temporarily unavailable");
        problem.setProperty("service", service);
        return problem;
    }
}
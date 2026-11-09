package com.msj.trading.bff.api;

import com.msj.trading.bff.security.InvalidAccessTokenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

    /** 401 so the SPA sends the user back through the login flow. */
    @ExceptionHandler(InvalidAccessTokenException.class)
    ProblemDetail handleInvalidAccessToken() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Session no longer valid, please log in again");
    }
}
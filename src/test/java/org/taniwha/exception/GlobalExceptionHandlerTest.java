package org.taniwha.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler globalExceptionHandler;

    @BeforeEach
    void setUp() {
        globalExceptionHandler = new GlobalExceptionHandler();
    }

    @Test
    void testHandleAllExceptions_RuntimeException() {
        Exception exception = new RuntimeException("Test runtime exception");

        ResponseEntity<String> response = globalExceptionHandler.handleAllExceptions(exception);

        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Error occurred: Test runtime exception", response.getBody());
    }

    @Test
    void testHandleAllExceptions_NullPointerException() {
        Exception exception = new NullPointerException("Null pointer test");

        ResponseEntity<String> response = globalExceptionHandler.handleAllExceptions(exception);

        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Error occurred: Null pointer test", response.getBody());
    }

    @Test
    void testHandleAllExceptions_IllegalArgumentException() {
        Exception exception = new IllegalArgumentException("Invalid argument");

        ResponseEntity<String> response = globalExceptionHandler.handleAllExceptions(exception);

        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Error occurred: Invalid argument", response.getBody());
    }

    @Test
    void testHandleAllExceptions_GenericException() {
        Exception exception = new Exception("Generic exception message");

        ResponseEntity<String> response = globalExceptionHandler.handleAllExceptions(exception);

        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Error occurred: Generic exception message", response.getBody());
    }

    @Test
    void testHandleAllExceptions_NullMessage() {
        Exception exception = new RuntimeException();

        ResponseEntity<String> response = globalExceptionHandler.handleAllExceptions(exception);

        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Error occurred: null", response.getBody());
    }
}

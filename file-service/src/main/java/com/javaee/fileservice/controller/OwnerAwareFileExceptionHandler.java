package com.javaee.fileservice.controller;

import com.javaee.fileservice.exception.OwnedFileNotFoundException;
import com.javaee.fileservice.exception.OwnedFileUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = OwnerAwareFileController.class)
public class OwnerAwareFileExceptionHandler {
    @ExceptionHandler(OwnedFileNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("errorCode", "OWNED_FILE_NOT_FOUND", "message", "Owned file does not exist"));
    }

    @ExceptionHandler(OwnedFileUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("errorCode", "OWNED_FILE_UNAVAILABLE", "message", "Owned file service is unavailable"));
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String, String>> unauthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("errorCode", "AUTHENTICATION_REQUIRED", "message", "Authentication is required"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidRequest() {
        return ResponseEntity.badRequest()
                .body(Map.of("errorCode", "OWNED_FILE_REQUEST_INVALID", "message", "Owned file request is invalid"));
    }
}

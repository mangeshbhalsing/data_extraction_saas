package com.dataextraction.saas.extraction;

public record ValidationIssue(String fieldName, String errorCode, String message) { }

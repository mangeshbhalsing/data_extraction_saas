package com.dataextraction.saas.extraction;

import java.util.Map;

public interface ExtractionProvider {
    boolean supports(String contentType);
    Map<String, Object> extract(byte[] content, String contentType, String documentType);
}

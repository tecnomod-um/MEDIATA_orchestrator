package org.taniwha.service;

import java.util.Map;

final class OntologySearchRequest {
    final String url;
    final String kind;
    final String query;
    final Map<String, ?> params;

    OntologySearchRequest(String url, String kind, String query, Map<String, ?> params) {
        this.url = url;
        this.kind = kind;
        this.query = query;
        this.params = params;
    }
}

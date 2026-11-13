package org.taniwha.service.enrichment;

public class TerminologyRequest {
    public final String term;
    public final String context;

    public TerminologyRequest(String term, String context) {
        this.term = term;
        this.context = context;
    }
}

package org.taniwha.service.enrichment;

final class LookupParts {
    final String lookupTerm;
    final String lookupContext;

    LookupParts(String lookupTerm, String lookupContext) {
        this.lookupTerm = lookupTerm;
        this.lookupContext = lookupContext;
    }
}

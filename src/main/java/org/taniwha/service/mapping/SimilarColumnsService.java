package org.taniwha.service.mapping;

import org.springframework.stereotype.Service;
import org.taniwha.dto.ColumnInFileDTO;
import org.taniwha.dto.SimilarColumnScoreDTO;
import org.taniwha.dto.SimilarColumnsRequestDTO;
import org.taniwha.service.EmbeddingService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class SimilarColumnsService {
    private static final int MAX_CANDIDATES = 250;
    private final EmbeddingService embeddingService;

    public SimilarColumnsService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    public List<SimilarColumnScoreDTO> rank(SimilarColumnsRequestDTO request) {
        if (request == null || request.getAnchor() == null || request.getCandidates() == null
                || request.getCandidates().size() > MAX_CANDIDATES) {
            return List.of();
        }

        ColumnInFileDTO anchor = request.getAnchor();
        float[] anchorVector = embed(anchor);
        if (isZero(anchorVector)) return List.of();

        List<SimilarColumnScoreDTO> scores = new ArrayList<>();
        for (ColumnInFileDTO candidate : request.getCandidates()) {
            if (candidate == null || candidate.getColumn() == null || sameColumn(anchor, candidate)) continue;
            float[] candidateVector = embed(candidate);
            if (isZero(candidateVector) || candidateVector.length != anchorVector.length) continue;
            double similarity = 0;
            for (int i = 0; i < anchorVector.length; i++) {
                similarity += (double) anchorVector[i] * candidateVector[i];
            }
            scores.add(new SimilarColumnScoreDTO(candidate.getNodeId(), candidate.getFileName(),
                    candidate.getColumn(), Math.max(-1, Math.min(1, similarity))));
        }
        scores.sort(Comparator.comparingDouble(SimilarColumnScoreDTO::score).reversed());
        return scores;
    }

    private float[] embed(ColumnInFileDTO column) {
        String name = column.getColumn() == null ? "" : column.getColumn();
        String term = label(column.getTerminology());
        if (!term.isEmpty()) name += " | " + term;
        return embeddingService.embedColumnWithValues(name, List.of());
    }

    private static String label(String terminology) {
        if (terminology == null) return "";
        String trimmed = terminology.trim();
        int separator = trimmed.lastIndexOf('|');
        return separator < 0 ? trimmed : trimmed.substring(0, separator).trim();
    }

    private static boolean isZero(float[] vector) {
        if (vector == null || vector.length == 0) return true;
        for (float value : vector) if (value != 0) return false;
        return true;
    }

    private static boolean sameColumn(ColumnInFileDTO left, ColumnInFileDTO right) {
        return java.util.Objects.equals(left.getNodeId(), right.getNodeId())
                && java.util.Objects.equals(left.getFileName(), right.getFileName())
                && java.util.Objects.equals(left.getColumn(), right.getColumn());
    }
}

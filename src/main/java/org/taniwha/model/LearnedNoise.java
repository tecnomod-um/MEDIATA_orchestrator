package org.taniwha.model;

import java.util.Map;
import java.util.Set;

public record LearnedNoise(Set<String> globalStopTokens, Set<String> suffixStopTokens, Map<String, Integer> df, int nCols) { }

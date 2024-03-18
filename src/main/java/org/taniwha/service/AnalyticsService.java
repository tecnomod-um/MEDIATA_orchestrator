package org.taniwha.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.stat.correlation.Covariance;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.correlation.SpearmansCorrelation;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.util.DateUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Service
public class AnalyticsService {

    @Async
    public CompletableFuture<AnalyticsResponseDTO> processAnalytics(MultipartFile file) {
        return process(file, Optional.empty(), Optional.empty());
    }

    @Async
    public CompletableFuture<AnalyticsResponseDTO> recalculateFeatureAsType(MultipartFile file, String featureName, String featureType) {
        return process(file, Optional.of(featureName), Optional.of(featureType));
    }

    private CompletableFuture<AnalyticsResponseDTO> process(MultipartFile file, Optional<String> overrideFeatureName, Optional<String> overrideFeatureType) {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            CSVFormat csvFormat = CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreEmptyLines(true).withDelimiter(autoDetectDelimiter(reader));
            CSVParser csvParser = new CSVParser(reader, csvFormat);

            Map<String, List<Double>> continuousData = new ConcurrentHashMap<>();
            Map<String, Map<String, Integer>> categoricalData = new ConcurrentHashMap<>();
            Map<String, List<String>> dateData = new ConcurrentHashMap<>();
            Map<String, Long> missingValueCounts = new ConcurrentHashMap<>();

            csvParser.getRecords().parallelStream().forEach(record -> processRecord(record, continuousData, categoricalData, dateData, missingValueCounts, overrideFeatureName, overrideFeatureType));

            long totalRecords = csvParser.getRecordNumber();
            if (overrideFeatureName.isPresent() && overrideFeatureType.isPresent()) {


                switch (overrideFeatureType.get().toLowerCase()) {
                    case "continuous":
                        // Check if the field was processed as date
                        if (dateData.containsKey(overrideFeatureName.get())) {
                            List<DateFeatureStatistics> dateStatistics = processDateData(Collections.singletonMap(overrideFeatureName.get(), dateData.get(overrideFeatureName.get())), missingValueCounts, totalRecords);
                            response.setDateFeatures(dateStatistics);
                            break;
                        } else {
                            List<FeatureStatistics> continuousStatistics = processContinuousData(Collections.singletonMap(overrideFeatureName.get(), continuousData.get(overrideFeatureName.get())), missingValueCounts, totalRecords);
                            response.setContinuousFeatures(continuousStatistics);
                        }
                        break;
                    case "categorical":
                        List<FeatureStatistics> categoricalStatistics = processCategoricalData(Collections.singletonMap(overrideFeatureName.get(), categoricalData.get(overrideFeatureName.get())), missingValueCounts, totalRecords);
                        response.setCategoricalFeatures(categoricalStatistics);
                        break;
                    default:
                        break;
                }
            } else {
                List<FeatureStatistics> continuousStatistics = processContinuousData(continuousData, missingValueCounts, totalRecords);
                List<FeatureStatistics> categoricalStatistics = processCategoricalData(categoricalData, missingValueCounts, totalRecords);
                List<DateFeatureStatistics> dateStatistics = processDateData(dateData, missingValueCounts, totalRecords);

                response.setDateFeatures(dateStatistics);
                response.setContinuousFeatures(continuousStatistics);
                response.setCategoricalFeatures(categoricalStatistics);
            }

            // Calc aggregate statistics
            Map<String, Map<String, Double>> covariances = calculateCovariances(continuousData);
            Map<String, Map<String, Double>> pearsonCorrelations = calculatePearsonCorrelations(continuousData);
            Map<String, Map<String, Double>> spearmanCorrelations = calculateSpearmanCorrelations(continuousData);

            response.setCovariances(covariances);
            response.setPearsonCorrelations(pearsonCorrelations);
            response.setSpearmanCorrelations(spearmanCorrelations);

            response.setMessage("Data processed successfully.");
        } catch (Exception e) {
            response.setMessage("Error processing file: " + e.getMessage());
        }
        return CompletableFuture.completedFuture(response);
    }

    private void processRecord(CSVRecord record, Map<String, List<Double>> continuousData, Map<String, Map<String, Integer>> categoricalData, Map<String, List<String>> dateData, Map<String, Long> missingValueCounts, Optional<String> overrideFeatureName, Optional<String> overrideFeatureType) {
        record.toMap().forEach((column, value) -> {
            String trimmedValue = value.trim();
            if (trimmedValue.isEmpty()) {
                missingValueCounts.merge(column, 1L, Long::sum);
                return;
            }
            String featureType = determineFeatureType(overrideFeatureName, overrideFeatureType, column, trimmedValue);

            switch (featureType) {
                case "date":
                    LocalDateTime parsedDate = DateUtils.parseDate(trimmedValue).orElseThrow();
                    dateData.computeIfAbsent(column, k -> new CopyOnWriteArrayList<>()).add(parsedDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
                    continuousData.remove(column);
                    categoricalData.remove(column);
                    break;
                case "continuous":
                    continuousData.computeIfAbsent(column, k -> new CopyOnWriteArrayList<>()).add(Double.parseDouble(trimmedValue));
                    categoricalData.remove(column);
                    break;
                case "categorical":
                    categoricalData.computeIfAbsent(column, k -> new ConcurrentHashMap<>()).merge(trimmedValue, 1, Integer::sum);
                    continuousData.remove(column);
                    dateData.remove(column);
                    break;
                default:
                    break;
            }
        });
    }

    private String determineFeatureType(Optional<String> overrideFeatureName, Optional<String> overrideFeatureType, String column, String value) {
        boolean isDate = DateUtils.parseDate(value).isPresent();
        if (overrideFeatureName.isPresent() && overrideFeatureName.get().equals(column)) {
            if (isDate && !overrideFeatureType.orElse("unknown").equalsIgnoreCase("categorical")) return "date";
            else return overrideFeatureType.orElse("unknown").toLowerCase();
        } else return isDate ? "date" : value.matches("-?\\d+(\\.\\d+)?") ? "continuous" : "categorical";
    }

    private char autoDetectDelimiter(BufferedReader reader) throws IOException {
        reader.mark(1024);
        String line = reader.readLine();
        reader.reset();
        return Stream.of(',', ';', '\t').max(Comparator.comparingInt(delimiter -> StringUtils.countMatches(line, delimiter))).orElse(',');
    }

    // Process categorical data for analytics
    private List<FeatureStatistics> processCategoricalData(Map<String, Map<String, Integer>> categoricalData, Map<String, Long> missingValueCounts, long totalRecords) {
        List<FeatureStatistics> statisticsList = new ArrayList<>();
        categoricalData.forEach((key, valueMap) -> {
            long missingValues = missingValueCounts.getOrDefault(key, 0L);
            double percentMissing = (double) missingValues / totalRecords * 100;
            List<Map.Entry<String, Integer>> sortedEntries = valueMap.entrySet().stream().sorted((entry1, entry2) -> entry2.getValue().compareTo(entry1.getValue())).toList();
            Map.Entry<String, Integer> modeEntry = sortedEntries.get(0);
            String mode = modeEntry.getKey();
            int modeFrequency = modeEntry.getValue();
            double modePercentage = (double) modeFrequency / totalRecords * 100;
            String secondMode = sortedEntries.size() > 1 ? sortedEntries.get(1).getKey() : null;
            Integer secondModeFrequency = secondMode != null ? valueMap.get(secondMode) : null;
            Double secondModePercentage = secondModeFrequency != null ? (double) secondModeFrequency / totalRecords * 100 : null;

            CategoricalFeatureStatistics stats = new CategoricalFeatureStatistics(key, totalRecords - missingValues, percentMissing, missingValues, valueMap.size(), mode, modeFrequency, modePercentage, secondMode, secondModeFrequency, secondModePercentage, valueMap);

            statisticsList.add(stats);
        });
        return statisticsList;
    }

    // Process continuous data for analytics
    private List<FeatureStatistics> processContinuousData(Map<String, List<Double>> continuousData, Map<String, Long> missingValueCounts, long totalRecords) {
        List<FeatureStatistics> statisticsList = new ArrayList<>();
        continuousData.forEach((key, valueList) -> {
            List<Double> outliers = identifyOutliers(valueList);
            double mean = valueList.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            double stddev = Math.sqrt(valueList.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum() / valueList.size());
            long missingValues = missingValueCounts.getOrDefault(key, 0L);
            double percentMissing = (double) missingValues / totalRecords * 100;
            double min = Collections.min(valueList);
            double max = Collections.max(valueList);
            double q1 = getPercentile(valueList, 25);
            double median = getPercentile(valueList, 50);
            double q3 = getPercentile(valueList, 75);
            Map<String, Object> histogramInfo = generateHistogram(valueList);

            List<Double> bins = (List<Double>) histogramInfo.get("bins");
            List<String> binRanges = (List<String>) histogramInfo.get("binRanges");

            statisticsList.add(new ContinuousFeatureStatistics(key, valueList.size(), percentMissing, missingValues, valueList.size(), min, max, mean, stddev, q1, median, q3, bins, binRanges, outliers));
        });
        return statisticsList;
    }

    // Process date data for analytics
    public List<DateFeatureStatistics> processDateData(Map<String, List<String>> dateData, Map<String, Long> missingValueCounts, long totalRecords) {
        List<DateFeatureStatistics> dateStatisticsList = new ArrayList<>();
        dateData.forEach((key, dateStringList) -> {
            List<LocalDate> dates = dateStringList.stream().map(LocalDate::parse).toList();
            List<Double> dateValues = dates.stream().mapToDouble(LocalDate::toEpochDay).boxed().collect(Collectors.toList());
            List<Double> outliers = identifyOutliers(dateValues);

            LocalDate earliestDate = dates.stream().min(LocalDate::compareTo).orElse(null);
            LocalDate latestDate = dates.stream().max(LocalDate::compareTo).orElse(null);
            long missingValues = missingValueCounts.getOrDefault(key, 0L);
            double percentMissing = (double) missingValues / totalRecords * 100;
            double meanEpoch = dateValues.stream().mapToDouble(v -> v).average().orElse(Double.NaN);
            LocalDate meanDate = LocalDate.ofEpochDay((long) meanEpoch);
            double medianEpoch = getPercentile(dateValues, 50);
            LocalDate medianDate = LocalDate.ofEpochDay((long) medianEpoch);
            double q1Epoch = getPercentile(dateValues, 25);
            LocalDate q1Date = LocalDate.ofEpochDay((long) q1Epoch);
            double q3Epoch = getPercentile(dateValues, 75);
            LocalDate q3Date = LocalDate.ofEpochDay((long) q3Epoch);
            double stdDevEpoch = Math.sqrt(dateValues.stream().mapToDouble(v -> Math.pow(v - meanEpoch, 2)).sum() / dateValues.size());

            List<String> outlierDates = outliers.stream().map(outlier -> LocalDate.ofEpochDay(outlier.longValue()).format(DateTimeFormatter.ISO_LOCAL_DATE)).collect(Collectors.toList());
            DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE;
            DateFeatureStatistics stats = new DateFeatureStatistics(key, dateStringList.size(), percentMissing, missingValues, earliestDate != null ? earliestDate.format(formatter) : "N/A", latestDate != null ? latestDate.format(formatter) : "N/A", dateStringList.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting())), outlierDates, meanDate.format(formatter), stdDevEpoch, medianDate.format(formatter), q1Date.format(formatter), q3Date.format(formatter));

            dateStatisticsList.add(stats);
        });
        return dateStatisticsList;
    }

    // Generate histogram for continuous data
    private Map<String, Object> generateHistogram(List<Double> data) {
        Map<String, Object> histogramInfo = new HashMap<>();
        if (data.isEmpty()) {
            histogramInfo.put("bins", Collections.emptyList());
            histogramInfo.put("binRanges", Collections.emptyList());
            return histogramInfo;
        }

        double min = Collections.min(data);
        double max = Collections.max(data);
        // Handle case where all data points are identical
        if (max == min) {
            histogramInfo.put("bins", Collections.singletonList((double) data.size()));
            histogramInfo.put("binRanges", Collections.singletonList(String.format(Locale.US, "[%f - %f]", min, max)));
            return histogramInfo;
        }

        // Calculate histogram bin width using the Freedman-Diaconis rule as a basis
        double range = max - min;
        double q1 = getPercentile(data, 25);
        double q3 = getPercentile(data, 75);
        double iqr = q3 - q1;
        double binWidth = 2.0 * iqr / Math.cbrt(data.size());
        binWidth = Math.max(binWidth, range / 10.0); // Ensure binWidth is not too small
        int binCount = (int) Math.ceil(range / binWidth);

        List<Double> bins = new ArrayList<>(Collections.nCopies(binCount, 0.0));
        double finalBinWidth = binWidth;
        data.forEach(value -> {
            int binIndex = (int) ((value - min) / finalBinWidth);
            binIndex = Math.min(binIndex, binCount - 1); // Ensure binIndex does not exceed binCount - 1
            bins.set(binIndex, bins.get(binIndex) + 1);
        });

        DecimalFormat df = new DecimalFormat("0.##", new DecimalFormatSymbols(Locale.US));
        List<String> binRanges = new ArrayList<>();
        for (int i = 0; i < binCount; i++) {
            double binMin = min + (i * binWidth);
            double binMax = binMin + binWidth;
            binRanges.add(String.format(Locale.US, "[%s - %s]", df.format(binMin), df.format(binMax)));
        }

        histogramInfo.put("bins", bins);
        histogramInfo.put("binRanges", binRanges);
        return histogramInfo;
    }

    // Calculate percentile of data
    private double getPercentile(List<Double> data, double percentile) {
        if (data.isEmpty()) return 0;
        List<Double> sortedData = new ArrayList<>(data);
        Collections.sort(sortedData);
        int index = (int) ((percentile / 100.0) * sortedData.size());
        return sortedData.get(Math.min(index, sortedData.size() - 1));
    }

    private List<Double> identifyOutliers(List<Double> data) {
        Collections.sort(data);
        double q1 = getPercentile(data, 25);
        double q3 = getPercentile(data, 75);
        double iqr = q3 - q1;
        double lowerBound = q1 - 1.5 * iqr;
        double upperBound = q3 + 1.5 * iqr;
        return data.stream().filter(x -> x < lowerBound || x > upperBound).collect(Collectors.toList());
    }

    private Map<String, Map<String, Double>> calculateCovariances(Map<String, List<Double>> continuousData) {
        Map<String, Map<String, Double>> covariances = new HashMap<>();
        Covariance covarianceCalculator = new Covariance();

        List<String> features = new ArrayList<>(continuousData.keySet());
        for (int i = 0; i < features.size(); i++) {
            String feature1 = features.get(i);
            for (int j = i + 1; j < features.size(); j++) {
                String feature2 = features.get(j);

                double[] data1 = continuousData.get(feature1).stream().mapToDouble(Double::doubleValue).toArray();
                double[] data2 = continuousData.get(feature2).stream().mapToDouble(Double::doubleValue).toArray();

                if (data1.length != data2.length || data1.length <= 1) {
                    continue;
                }

                double covariance = covarianceCalculator.covariance(data1, data2, false);
                covariances.computeIfAbsent(feature1, k -> new HashMap<>()).put(feature2, covariance);
                covariances.computeIfAbsent(feature2, k -> new HashMap<>()).put(feature1, covariance);
            }
        }
        return covariances;
    }

    private Map<String, Map<String, Double>> calculatePearsonCorrelations(Map<String, List<Double>> continuousData) {
        Map<String, Map<String, Double>> correlations = new HashMap<>();
        PearsonsCorrelation correlationCalculator = new PearsonsCorrelation();

        List<String> features = new ArrayList<>(continuousData.keySet());
        for (int i = 0; i < features.size(); i++) {
            for (int j = i + 1; j < features.size(); j++) {
                String feature1 = features.get(i);
                String feature2 = features.get(j);

                double[] data1 = continuousData.get(feature1).stream().mapToDouble(Double::doubleValue).toArray();
                double[] data2 = continuousData.get(feature2).stream().mapToDouble(Double::doubleValue).toArray();


                if (data1.length != data2.length || data1.length <= 1) {
                    continue;
                }

                // Calculate correlation and add to map if valid (not NaN)
                double correlation = correlationCalculator.correlation(data1, data2);
                if (!Double.isNaN(correlation)) {
                    correlations.computeIfAbsent(feature1, k -> new HashMap<>()).put(feature2, correlation);
                    correlations.computeIfAbsent(feature2, k -> new HashMap<>()).put(feature1, correlation);
                }
            }
        }
        return correlations;
    }

    private Map<String, Map<String, Double>> calculateSpearmanCorrelations(Map<String, List<Double>> continuousData) {
        Map<String, Map<String, Double>> spearmanCorrelations = new HashMap<>();
        SpearmansCorrelation spearmanCorrelationCalculator = new SpearmansCorrelation();

        List<String> features = new ArrayList<>(continuousData.keySet());
        for (int i = 0; i < features.size(); i++) {
            String feature1 = features.get(i);
            for (int j = i + 1; j < features.size(); j++) {
                String feature2 = features.get(j);

                double[] data1 = continuousData.get(feature1).stream().mapToDouble(Double::doubleValue).toArray();
                double[] data2 = continuousData.get(feature2).stream().mapToDouble(Double::doubleValue).toArray();

                if (data1.length != data2.length || data1.length <= 1) {
                    continue;
                }

                double correlation = spearmanCorrelationCalculator.correlation(data1, data2);
                if (!Double.isNaN(correlation)) {
                    spearmanCorrelations.computeIfAbsent(feature1, k -> new HashMap<>()).put(feature2, correlation);
                    spearmanCorrelations.computeIfAbsent(feature2, k -> new HashMap<>()).put(feature1, correlation);
                }
            }
        }
        return spearmanCorrelations;
    }
}

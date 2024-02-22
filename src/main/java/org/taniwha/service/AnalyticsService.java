package org.taniwha.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.util.DateUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    @Async
    public CompletableFuture<AnalyticsResponseDTO> processAnalytics(MultipartFile file) {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            // Detect the separator char of the csv
            CSVFormat csvFormat = CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreEmptyLines(true).withDelimiter(autoDetectDelimiter(reader));
            CSVParser csvParser = new CSVParser(reader, csvFormat);

            // Data structures for analytics
            Map<String, List<Double>> continuousData = new ConcurrentHashMap<>();
            Map<String, Map<String, Integer>> categoricalData = new ConcurrentHashMap<>();
            Map<String, List<String>> dateData = new ConcurrentHashMap<>();
            Map<String, Long> missingValueCounts = new ConcurrentHashMap<>();
            Map<String, List<Double>> histograms = new ConcurrentHashMap<>();
            Map<String, List<Integer>> barCharts = new ConcurrentHashMap<>();

            // Preprocess each record in parallel
            csvParser.getRecords().parallelStream().forEach(record -> processRecord(record, continuousData, categoricalData, dateData, missingValueCounts));

            // Generate analytics
            Map<String, DateStatistics> dateStatistics = processDateData(dateData, missingValueCounts, csvParser.getRecordNumber());
            List<FeatureStatistics> continuousStatistics = processContinuousData(continuousData, histograms, missingValueCounts, csvParser.getRecordNumber());
            List<FeatureStatistics> categoricalStatistics = processCategoricalData(categoricalData, barCharts, missingValueCounts, csvParser.getRecordNumber());

            // Set analytics to response object
            response.setDateStatistics(dateStatistics);
            response.setContinuousFeatures(continuousStatistics);
            response.setCategoricalFeatures(categoricalStatistics);
            response.setHistograms(histograms);
            response.setMessage("Data processed successfully.");
        } catch (Exception e) {
            response.setMessage("Error processing file: " + e.getMessage());
        }
        return CompletableFuture.completedFuture(response);
    }

    private char autoDetectDelimiter(BufferedReader reader) throws Exception {
        reader.mark(1);
        String line = reader.readLine();
        char[] delimiters = {',', ';', '\t'};
        int maxCount = 0;
        char selectedDelimiter = ',';
        for (char delimiter : delimiters) {
            int count = 0;
            for (char c : line.toCharArray()) {
                if (c == delimiter) {
                    count++;
                }
            }
            if (count > maxCount) {
                maxCount = count;
                selectedDelimiter = delimiter;
            }
        }
        reader.reset();
        return selectedDelimiter;
    }

    // Check if the fields of the csv are dates or absent
    private void processRecord(CSVRecord record, Map<String, List<Double>> continuousData, Map<String, Map<String, Integer>> categoricalData, Map<String, List<String>> dateData, Map<String, Long> missingValueCounts) {
        record.toMap().forEach((column, value) -> {
            String trimmedValue = value.trim();
            if (!trimmedValue.isEmpty()) {
                Optional<LocalDateTime> parsedDateOpt = DateUtils.parseDate(trimmedValue);
                if (parsedDateOpt.isPresent()) {
                    LocalDateTime parsedDate = parsedDateOpt.get();
                    dateData.computeIfAbsent(column, k -> new CopyOnWriteArrayList<>()).add(parsedDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
                } else if (trimmedValue.matches("-?\\d+(\\.\\d+)?"))
                    continuousData.computeIfAbsent(column, k -> new CopyOnWriteArrayList<>()).add(Double.parseDouble(trimmedValue));
                else
                    categoricalData.computeIfAbsent(column, k -> new ConcurrentHashMap<>()).merge(trimmedValue, 1, Integer::sum);
            } else {
                missingValueCounts.merge(column, 1L, Long::sum);
            }
        });
    }

    // Process continuous data for analytics
    private List<FeatureStatistics> processContinuousData(Map<String, List<Double>> continuousData, Map<String, List<Double>> histograms, Map<String, Long> missingValueCounts, long totalRecords) {
        List<FeatureStatistics> statisticsList = new ArrayList<>();
        continuousData.forEach((key, valueList) -> {
            // Sort the data for percentile calculation
            Collections.sort(valueList);
            double q1 = getPercentile(valueList, 25);
            double q3 = getPercentile(valueList, 75);
            double iqr = q3 - q1;
            double lowerBound = q1 - 1.5 * iqr;
            double upperBound = q3 + 1.5 * iqr;
            // Detect outliers
            List<Double> outliers = valueList.stream().filter(x -> x < lowerBound || x > upperBound).collect(Collectors.toList());
            // Calculate statistics
            double mean = valueList.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            double variance = valueList.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum() / valueList.size();
            double stddev = Math.sqrt(variance);
            long missingValues = missingValueCounts.getOrDefault(key, 0L);
            double percentMissing = (double) missingValues / totalRecords * 100;

            Map<String, Object> statistics = new HashMap<>();
            statistics.put("Count", valueList.size());
            statistics.put("Min", Collections.min(valueList));
            statistics.put("Max", Collections.max(valueList));
            statistics.put("Mean", mean);
            statistics.put("StdDev", stddev);
            statistics.put("MissingValues", missingValues);
            statistics.put("Outliers", outliers);

            histograms.put(key, generateHistogram(valueList));
            statisticsList.add(new FeatureStatistics(statistics, valueList.size(), percentMissing, valueList.size(), missingValues, key, outliers));
        });
        return statisticsList;
    }

    // Process categorical data for analytics
    private List<FeatureStatistics> processCategoricalData(Map<String, Map<String, Integer>> categoricalData, Map<String, List<Integer>> barCharts, Map<String, Long> missingValueCounts, long totalRecords) {
        List<FeatureStatistics> statisticsList = new ArrayList<>();
        categoricalData.forEach((key, valueMap) -> {
            long missingValues = missingValueCounts.getOrDefault(key, 0L);
            double percentMissing = (double) missingValues / totalRecords * 100;
            Map<String, Object> statistics = new HashMap<>(valueMap);
            statistics.put("MissingValues", missingValues);
            barCharts.put(key, new ArrayList<>(valueMap.values()));
            statisticsList.add(new FeatureStatistics(statistics, valueMap.size(), percentMissing, valueMap.values().stream().mapToInt(Integer::intValue).sum(), missingValues, key, new ArrayList<>()));
        });
        return statisticsList;
    }

    // Process date data for analytics
    private Map<String, DateStatistics> processDateData(Map<String, List<String>> dateData, Map<String, Long> missingValueCounts, long totalRecords) {
        Map<String, DateStatistics> dateStatisticsMap = new HashMap<>();
        dateData.forEach((key, dateList) -> {
            LocalDate earliestDate = dateList.stream().map(LocalDate::parse).min(LocalDate::compareTo).orElse(null);
            LocalDate latestDate = dateList.stream().map(LocalDate::parse).max(LocalDate::compareTo).orElse(null);
            long missingValues = missingValueCounts.getOrDefault(key, 0L);
            Map<String, Long> histogram = new HashMap<>();
            dateList.forEach(date -> histogram.merge(date, 1L, Long::sum));
            DateStatistics statistics = new DateStatistics(dateList.size(), missingValues, earliestDate != null ? earliestDate.toString() : "N/A", latestDate != null ? latestDate.toString() : "N/A");
            statistics.setDateHistogram(histogram);
            dateStatisticsMap.put(key, statistics);
        });
        return dateStatisticsMap;
    }

    // Generate histogram for continuous data
    private List<Double> generateHistogram(List<Double> data) {
        if (data.isEmpty()) return Collections.emptyList();
        double min = Collections.min(data);
        double max = Collections.max(data);
        if (max == min) return Collections.singletonList((double) data.size());
        double range = max - min;
        double q1 = getPercentile(data, 25);
        double q3 = getPercentile(data, 75);
        double iqr = q3 - q1;
        double binWidth = 2.0 * iqr / Math.cbrt(data.size());
        binWidth = Math.max(binWidth, range / 10.0);
        int binCount = (int) Math.ceil(range / binWidth);
        List<Double> histogram = new ArrayList<>(Collections.nCopies(binCount, 0.0));
        for (Double value : data) {
            int binIndex = (int) ((value - min) / binWidth);
            binIndex = Math.min(binIndex, binCount - 1);
            histogram.set(binIndex, histogram.get(binIndex) + 1);
        }
        return histogram;
    }

    // Calculate percentile of data
    private double getPercentile(List<Double> data, double percentile) {
        if (data.isEmpty()) return 0;
        List<Double> sortedData = new ArrayList<>(data);
        Collections.sort(sortedData);
        int index = (int) ((percentile / 100.0) * sortedData.size());
        return sortedData.get(Math.min(index, sortedData.size() - 1));
    }
}

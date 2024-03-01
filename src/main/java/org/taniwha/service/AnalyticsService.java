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

@Service
public class AnalyticsService {

    @Async
    public CompletableFuture<AnalyticsResponseDTO> processAnalytics(MultipartFile file) {
        AnalyticsResponseDTO response = new AnalyticsResponseDTO();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            CSVFormat csvFormat = CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreEmptyLines(true).withDelimiter(autoDetectDelimiter(reader));
            CSVParser csvParser = new CSVParser(reader, csvFormat);

            Map<String, List<Double>> continuousData = new ConcurrentHashMap<>();
            Map<String, Map<String, Integer>> categoricalData = new ConcurrentHashMap<>();
            Map<String, List<String>> dateData = new ConcurrentHashMap<>();
            Map<String, Long> missingValueCounts = new ConcurrentHashMap<>();

            csvParser.getRecords().parallelStream().forEach(record -> processRecord(record, continuousData, categoricalData, dateData, missingValueCounts));

            List<FeatureStatistics> continuousStatistics = processContinuousData(continuousData, missingValueCounts, csvParser.getRecordNumber());
            List<FeatureStatistics> categoricalStatistics = processCategoricalData(categoricalData, missingValueCounts, csvParser.getRecordNumber());
            Map<String, DateFeatureStatistics> dateStatistics = processDateData(dateData, missingValueCounts, csvParser.getRecordNumber());

            response.setDateStatistics(dateStatistics);
            response.setContinuousFeatures(continuousStatistics);
            response.setCategoricalFeatures(categoricalStatistics);
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
                if (c == delimiter) count++;
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
                    // TODO manually define categories
                    continuousData.computeIfAbsent(column, k -> new CopyOnWriteArrayList<>()).add(Double.parseDouble(trimmedValue));
                else
                    categoricalData.computeIfAbsent(column, k -> new ConcurrentHashMap<>()).merge(trimmedValue, 1, Integer::sum);
            } else {
                missingValueCounts.merge(column, 1L, Long::sum);
            }
        });
    }

    // Process categorical data for analytics
    private List<FeatureStatistics> processCategoricalData(Map<String, Map<String, Integer>> categoricalData, Map<String, Long> missingValueCounts, long totalRecords) {
        List<FeatureStatistics> statisticsList = new ArrayList<>();
        categoricalData.forEach((key, valueMap) -> {
            long missingValues = missingValueCounts.getOrDefault(key, 0L);
            double percentMissing = (double) missingValues / totalRecords * 100;
            List<Map.Entry<String, Integer>> sortedEntries = valueMap.entrySet().stream()
                    .sorted((entry1, entry2) -> entry2.getValue().compareTo(entry1.getValue()))
                    .toList();
            Map.Entry<String, Integer> modeEntry = sortedEntries.get(0);
            String mode = modeEntry.getKey();
            int modeFrequency = modeEntry.getValue();
            double modePercentage = (double) modeFrequency / totalRecords * 100;
            String secondMode = sortedEntries.size() > 1 ? sortedEntries.get(1).getKey() : null;
            Integer secondModeFrequency = secondMode != null ? valueMap.get(secondMode) : null;
            Double secondModePercentage = secondModeFrequency != null ? (double) secondModeFrequency / totalRecords * 100 : null;

            CategoricalFeatureStatistics stats = new CategoricalFeatureStatistics(
                    key, totalRecords - missingValues, percentMissing, missingValues, valueMap.size(),
                    mode, modeFrequency, modePercentage, secondMode, secondModeFrequency,
                    secondModePercentage, valueMap);

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

            statisticsList.add(new ContinuousFeatureStatistics(
                    key, valueList.size(), percentMissing, missingValues, valueList.size(),
                    min, max, mean, stddev, q1, median, q3, bins, binRanges, outliers));
        });
        return statisticsList;
    }

    // Process date data for analytics
    private Map<String, DateFeatureStatistics> processDateData(Map<String, List<String>> dateData, Map<String, Long> missingValueCounts, long totalRecords) {
        Map<String, DateFeatureStatistics> dateStatisticsMap = new HashMap<>();
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
            dateStatisticsMap.put(key, new DateFeatureStatistics(key, dateStringList.size(), percentMissing, missingValues, earliestDate != null ? earliestDate.format(formatter) : "N/A", latestDate != null ? latestDate.format(formatter) : "N/A", dateStringList.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting())), outlierDates, meanDate.format(formatter), stdDevEpoch, medianDate.format(formatter), q1Date.format(formatter), q3Date.format(formatter)));
        });
        return dateStatisticsMap;
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
}

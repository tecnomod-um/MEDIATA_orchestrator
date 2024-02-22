package org.taniwha.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

public class DateUtils {

    private static final String[] DATE_TIME_FORMATS = {"d/M/yyyy H:m", "d/M/yyyy", "M/d/yyyy H:m", "M/d/yyyy", "yyyy/M/d H:m", "yyyy/M/d", "d-M-yyyy H:m", "d-M-yyyy", "M-d-yyyy H:m", "M-d-yyyy", "yyyy-M-d H:m", "yyyy-M-d", "d.M.yyyy H:m", "d.M.yyyy", "M.d.yyyy H:m", "M.d.yyyy", "yyyy.M.d H:m", "yyyy.M.d", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy", "MM/dd/yyyy HH:mm:ss", "MM/dd/yyyy", "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd", "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy", "MM-dd-yyyy HH:mm:ss", "MM-dd-yyyy", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "dd.MM.yyyy HH:mm:ss", "dd.MM.yyyy", "MM.dd.yyyy HH:mm:ss", "MM.dd.yyyy", "yyyy.MM.dd HH:mm:ss", "yyyy.MM.dd"};

    public static Optional<LocalDateTime> parseDate(String value) {
        for (String pattern : DATE_TIME_FORMATS) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            try {
                LocalDateTime dateTime = LocalDateTime.parse(value, formatter);
                return Optional.of(dateTime);
            } catch (DateTimeParseException ignored) {
                try {
                    LocalDate date = LocalDate.parse(value, formatter);
                    return Optional.of(date.atStartOfDay());
                } catch (DateTimeParseException ignored2) {
                }
            }
        }
        return Optional.empty();
    }
}

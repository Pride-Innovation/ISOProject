package com.pridebank.isoproject.validation;

import com.solab.iso8583.IsoMessage;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Component
public class IsoValidator {

    private static final SimpleDateFormat FIELD7_FMT = new SimpleDateFormat("MMddHHmmss");

    /**
     * Strict validation only applies to financial request MTI 0200.
     * For other MTIs (0800 echo/sign-on, 0420 reversal, etc.) return OK or perform light checks elsewhere.
     */
    public ValidationResult validate0200(IsoMessage m) {
        List<String> errors = new ArrayList<>();
        if (m == null) {
            errors.add("Empty request");
            return ValidationResult.failed(errors);
        }

        // If not a 0200 request, skip strict 0200 checks (caller may apply MTI-specific validation)
        if (m.getType() != 0x200) {
            return ValidationResult.ok();
        }

        // Strict checks for 0200
        int[] required = {2, 3/*4 optional per config*/, 7, 11, 41 /*, 49 optional */};
        for (int f : required) {
            if (!m.hasField(f)) errors.add("Missing field " + f);
        }

        if (m.hasField(2)) {
            String pan = m.getObjectValue(2).toString().trim();
            if (pan.length() < 13 || pan.length() > 19 || !pan.matches("\\d+"))
                errors.add("Field 2 PAN invalid");
        }
        if (m.hasField(3) && !m.getObjectValue(3).toString().matches("\\d{6}"))
            errors.add("Field 3 must be 6 numeric");
        if (m.hasField(4) && !m.getObjectValue(4).toString().matches("\\d{12}"))
            errors.add("Field 4 must be 12 numeric (minor units)");

        if (m.hasField(7)) {
            Object v7 = m.getObjectValue(7);
            String v7str = null;
            if (v7 instanceof Date) {
                v7str = FIELD7_FMT.format((Date) v7);
            } else if (v7 != null) {
                v7str = v7.toString();
            }
            if (v7str == null || !v7str.matches("\\d{10}"))
                errors.add("Field 7 must be MMddHHmmss");
        }

        if (m.hasField(11) && !m.getObjectValue(11).toString().matches("\\d{6}"))
            errors.add("Field 11 must be 6 numeric");
        if (m.hasField(41) && m.getObjectValue(41).toString().trim().isEmpty())
            errors.add("Field 41 terminalId required");
        if (m.hasField(49) && !m.getObjectValue(49).toString().matches("\\d{3}"))
            errors.add("Field 49 must be 3 numeric");

        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(errors);
    }

    @Getter
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;

        private ValidationResult(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = errors;
        }

        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult failed(List<String> errors) {
            return new ValidationResult(false, errors);
        }

        public String summary() {
            return String.join("; ", errors);
        }
    }
}
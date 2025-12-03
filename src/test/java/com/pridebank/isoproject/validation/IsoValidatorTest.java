package com.pridebank.isoproject.validation;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoValue;
import com.solab.iso8583.IsoType;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.*;

class IsoValidatorTest {

    private final IsoValidator validator = new IsoValidator();

    @Test
    void validate0200_validMessage_returnsOk() {
        IsoMessage m = new IsoMessage();
        m.setType(0x200);
        m.setField(2, new IsoValue<>(IsoType.LLVAR, "4123456789012", 13));
        m.setField(3, new IsoValue<>(IsoType.NUMERIC, "000000", 6));
        m.setField(4, new IsoValue<>(IsoType.NUMERIC, "000000000500", 12));
        m.setField(7, new IsoValue<>(IsoType.DATE10, new Date(), 10));
        m.setField(11, new IsoValue<>(IsoType.NUMERIC, "000001", 6));
        m.setField(41, new IsoValue<>(IsoType.ALPHA, "ATM00001", 8));
        m.setField(49, new IsoValue<>(IsoType.NUMERIC, "566", 3));

        IsoValidator.ValidationResult vr = validator.validate0200(m);
        assertThat(vr.isValid()).isTrue();
    }

    @Test
    void validate0200_missingFields_returnsFailed() {
        IsoMessage m = new IsoMessage();
        m.setType(0x200);
        // missing required fields
        IsoValidator.ValidationResult vr = validator.validate0200(m);
        assertThat(vr.isValid()).isFalse();
        assertThat(vr.getErrors()).isNotEmpty();
    }

    @Test
    void validate0200_invalidField7String_returnsFailed() {
        IsoMessage m = new IsoMessage();
        m.setType(0x200);
        m.setField(2, new IsoValue<>(IsoType.LLVAR, "4123456789012", 13));
        m.setField(3, new IsoValue<>(IsoType.NUMERIC, "000000", 6));
        m.setField(4, new IsoValue<>(IsoType.NUMERIC, "000000000500", 12));
        // set field7 as wrong string
        m.setField(7, new IsoValue<>(IsoType.DATE10, "bad", 10));
        m.setField(11, new IsoValue<>(IsoType.NUMERIC, "000001", 6));
        m.setField(41, new IsoValue<>(IsoType.ALPHA, "ATM00001", 8));
        m.setField(49, new IsoValue<>(IsoType.NUMERIC, "566", 3));

        IsoValidator.ValidationResult vr = validator.validate0200(m);
        assertThat(vr.isValid()).isFalse();
        assertThat(vr.getErrors()).anyMatch(s -> s.contains("Field 7"));
    }
}
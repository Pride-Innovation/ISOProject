package com.pridebank.isoproject.config;

import com.solab.iso8583.*;
import com.solab.iso8583.parse.*;
import com.solab.iso8583.parse.date.Date10ParseInfo;
import lombok.extern.slf4j.Slf4j;
import org.jpos.iso.packager.GenericPackager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
public class IsoConfig {
    private IsoMessage base(int mti) {
        IsoMessage m = new IsoMessage();
        m.setType(mti);
        m.setField(2, new IsoValue<>(IsoType.LLVAR, ""));
        m.setField(3, new IsoValue<>(IsoType.NUMERIC, "000000", 6));
        m.setField(4, new IsoValue<>(IsoType.NUMERIC, "000000000000", 12));
        m.setField(28, new IsoValue<>(IsoType.ALPHA, "C00000000", 9));
        m.setField(29, new IsoValue<>(IsoType.ALPHA, "C00000000", 9));
        m.setField(30, new IsoValue<>(IsoType.ALPHA, "C00000000", 9));
        m.setField(31, new IsoValue<>(IsoType.ALPHA, "C00000000", 9));
        m.setField(5, new IsoValue<>(IsoType.NUMERIC, "000000000000", 12));
        m.setField(6, new IsoValue<>(IsoType.NUMERIC, "000000000000", 12));
        m.setField(7, new IsoValue<>(IsoType.DATE10, new java.util.Date(), 10));
        m.setField(8, new IsoValue<>(IsoType.NUMERIC, "0", 8));
        m.setField(9, new IsoValue<>(IsoType.NUMERIC, "0", 8));
        m.setField(10, new IsoValue<>(IsoType.NUMERIC, "0", 8));
        m.setField(11, new IsoValue<>(IsoType.NUMERIC, "000000", 6));
        m.setField(12, new IsoValue<>(IsoType.NUMERIC, "000000", 6));
        m.setField(13, new IsoValue<>(IsoType.NUMERIC, "0000", 4));
        m.setField(14, new IsoValue<>(IsoType.NUMERIC, "0000", 4));
        m.setField(15, new IsoValue<>(IsoType.NUMERIC, "0000", 4));
        m.setField(16, new IsoValue<>(IsoType.NUMERIC, "0000", 4));
        m.setField(17, new IsoValue<>(IsoType.NUMERIC, "0000", 4));
        m.setField(18, new IsoValue<>(IsoType.NUMERIC, "0000", 4));
        m.setField(19, new IsoValue<>(IsoType.NUMERIC, "000", 3));
        m.setField(20, new IsoValue<>(IsoType.NUMERIC, "000", 3));
        m.setField(21, new IsoValue<>(IsoType.NUMERIC, "000", 3));
        m.setField(22, new IsoValue<>(IsoType.NUMERIC, "000", 3));
        m.setField(23, new IsoValue<>(IsoType.NUMERIC, "000", 3));
        m.setField(24, new IsoValue<>(IsoType.NUMERIC, "000", 3));
        m.setField(25, new IsoValue<>(IsoType.NUMERIC, "00", 2));
        m.setField(26, new IsoValue<>(IsoType.NUMERIC, "00", 2));
        m.setField(27, new IsoValue<>(IsoType.NUMERIC, "0", 1));

        m.setField(32, new IsoValue<>(IsoType.LLVAR, ""));
        m.setField(33, new IsoValue<>(IsoType.LLVAR, ""));
        m.setField(34, new IsoValue<>(IsoType.LLVAR, ""));
        m.setField(35, new IsoValue<>(IsoType.LLVAR, ""));
        m.setField(36, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(37, new IsoValue<>(IsoType.ALPHA, "", 12));
        m.setField(38, new IsoValue<>(IsoType.ALPHA, "      ", 6));
        m.setField(39, new IsoValue<>(IsoType.ALPHA, "00", 2));
        m.setField(40, new IsoValue<>(IsoType.ALPHA, "000", 3));
        m.setField(41, new IsoValue<>(IsoType.ALPHA, "        ", 8));
        m.setField(42, new IsoValue<>(IsoType.ALPHA, "", 15));
        m.setField(43, new IsoValue<>(IsoType.ALPHA, "", 40));
        m.setField(44, new IsoValue<>(IsoType.LLVAR, ""));
        m.setField(45, new IsoValue<>(IsoType.LLVAR, ""));
        m.setField(46, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(47, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(48, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(49, new IsoValue<>(IsoType.NUMERIC, "566", 3));
        m.setField(50, new IsoValue<>(IsoType.NUMERIC, "000", 3));
        m.setField(51, new IsoValue<>(IsoType.NUMERIC, "000", 3));
        m.setField(52, new IsoValue<>(IsoType.BINARY, new byte[8], 8));
        m.setField(53, new IsoValue<>(IsoType.BINARY, new byte[48], 48));
        m.setField(54, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(55, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(56, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(57, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(58, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(59, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(60, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(61, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(62, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(63, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(64, new IsoValue<>(IsoType.BINARY, new byte[8], 8));

        m.setField(70, new IsoValue<>(IsoType.NUMERIC, "000", 3));
        m.setField(71, new IsoValue<>(IsoType.NUMERIC, "0", 4));
        m.setField(72, new IsoValue<>(IsoType.NUMERIC, "0", 4));
        m.setField(73, new IsoValue<>(IsoType.NUMERIC, "0", 6));
        m.setField(74, new IsoValue<>(IsoType.NUMERIC, "0", 10));
        m.setField(75, new IsoValue<>(IsoType.NUMERIC, "0", 10));
        m.setField(76, new IsoValue<>(IsoType.NUMERIC, "0", 10));
        m.setField(77, new IsoValue<>(IsoType.NUMERIC, "0", 10));
        m.setField(78, new IsoValue<>(IsoType.NUMERIC, "0", 10));
        m.setField(79, new IsoValue<>(IsoType.NUMERIC, "0", 10));
        m.setField(80, new IsoValue<>(IsoType.NUMERIC, "0", 10));
        m.setField(81, new IsoValue<>(IsoType.NUMERIC, "0", 10));
        m.setField(82, new IsoValue<>(IsoType.NUMERIC, "0", 12));
        m.setField(83, new IsoValue<>(IsoType.NUMERIC, "0", 12));
        m.setField(84, new IsoValue<>(IsoType.NUMERIC, "0", 12));
        m.setField(85, new IsoValue<>(IsoType.NUMERIC, "0", 12));
        m.setField(86, new IsoValue<>(IsoType.NUMERIC, "0", 16));
        m.setField(87, new IsoValue<>(IsoType.NUMERIC, "0", 16));
        m.setField(88, new IsoValue<>(IsoType.NUMERIC, "0", 16));
        m.setField(89, new IsoValue<>(IsoType.NUMERIC, "0", 16));
        m.setField(90, new IsoValue<>(IsoType.LLVAR, ""));
        m.setField(91, new IsoValue<>(IsoType.ALPHA, " ", 1));
        m.setField(92, new IsoValue<>(IsoType.ALPHA, "  ", 2));
        m.setField(93, new IsoValue<>(IsoType.ALPHA, "      ", 6));
        m.setField(94, new IsoValue<>(IsoType.ALPHA, "       ", 7));
        m.setField(95, new IsoValue<>(IsoType.ALPHA, "", 42));
        m.setField(96, new IsoValue<>(IsoType.BINARY, new byte[16], 16));
        m.setField(97, new IsoValue<>(IsoType.AMOUNT, "0", 17));
        m.setField(98, new IsoValue<>(IsoType.ALPHA, "", 25));
        m.setField(99, new IsoValue<>(IsoType.LLVAR, ""));
        m.setField(100, new IsoValue<>(IsoType.LLVAR, ""));
        m.setField(101, new IsoValue<>(IsoType.LLVAR, ""));
        m.setField(102, new IsoValue<>(IsoType.LLVAR, ""));
        m.setField(103, new IsoValue<>(IsoType.LLVAR, ""));
        m.setField(104, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(120, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(121, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(122, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(123, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(124, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(125, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(126, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(127, new IsoValue<>(IsoType.LLLVAR, ""));
        m.setField(128, new IsoValue<>(IsoType.BINARY, new byte[8], 8));

        return m;
    }

    private Map<Integer, FieldParseInfo> parseMap0200() {
        Map<Integer, FieldParseInfo> map = new HashMap<>();

        map.put(2, new LlvarParseInfo());
        map.put(3, new NumericParseInfo(6));
        map.put(4, new NumericParseInfo(12));
        map.put(5, new NumericParseInfo(12));
        map.put(6, new NumericParseInfo(12));
        map.put(7, new Date10ParseInfo());

        map.put(28, new AlphaParseInfo(9));
        map.put(29, new AlphaParseInfo(9));
        map.put(30, new AlphaParseInfo(9));
        map.put(31, new AlphaParseInfo(9));

        map.put(8, new NumericParseInfo(8));
        map.put(9, new NumericParseInfo(8));
        map.put(10, new NumericParseInfo(8));
        map.put(11, new NumericParseInfo(6));
        map.put(12, new NumericParseInfo(6));
        map.put(13, new NumericParseInfo(4));
        map.put(14, new NumericParseInfo(4));
        map.put(15, new NumericParseInfo(4));
        map.put(16, new NumericParseInfo(4));
        map.put(17, new NumericParseInfo(4));
        map.put(18, new NumericParseInfo(4));
        map.put(19, new NumericParseInfo(3));
        map.put(20, new NumericParseInfo(3));
        map.put(21, new NumericParseInfo(3));
        map.put(22, new NumericParseInfo(3));
        map.put(23, new NumericParseInfo(3));
        map.put(24, new NumericParseInfo(3));
        map.put(25, new NumericParseInfo(2));
        map.put(26, new NumericParseInfo(2));
        map.put(27, new NumericParseInfo(1));

        map.put(32, new LlvarParseInfo());
        map.put(33, new LlvarParseInfo());
        map.put(34, new LlvarParseInfo());
        map.put(35, new LlvarParseInfo());
        map.put(36, new LllvarParseInfo());
        map.put(37, new AlphaParseInfo(12));
        map.put(38, new AlphaParseInfo(6));
        map.put(39, new AlphaParseInfo(2));
        map.put(40, new AlphaParseInfo(3));
        map.put(41, new AlphaParseInfo(8));
        map.put(42, new AlphaParseInfo(15));
        map.put(43, new AlphaParseInfo(40));
        map.put(44, new LlvarParseInfo());
        map.put(45, new LlvarParseInfo());
        map.put(46, new LllvarParseInfo());
        map.put(47, new LllvarParseInfo());
        map.put(48, new LllvarParseInfo());
        map.put(49, new NumericParseInfo(3));
        map.put(50, new NumericParseInfo(3));
        map.put(51, new NumericParseInfo(3));
        map.put(52, new BinaryParseInfo(8));
        map.put(53, new BinaryParseInfo(48));
        map.put(54, new LllvarParseInfo());
        map.put(55, new LllvarParseInfo());
        map.put(56, new LllvarParseInfo());
        map.put(57, new LllvarParseInfo());
        map.put(58, new LllvarParseInfo());
        map.put(59, new LllvarParseInfo());
        map.put(60, new LllvarParseInfo());
        map.put(61, new LllvarParseInfo());
        map.put(62, new LllvarParseInfo());
        map.put(63, new LllvarParseInfo());
        map.put(64, new BinaryParseInfo(8));

        map.put(70, new NumericParseInfo(3));
        map.put(71, new NumericParseInfo(4));
        map.put(72, new NumericParseInfo(4));
        map.put(73, new NumericParseInfo(6));
        map.put(74, new NumericParseInfo(10));
        map.put(75, new NumericParseInfo(10));
        map.put(76, new NumericParseInfo(10));
        map.put(77, new NumericParseInfo(10));
        map.put(78, new NumericParseInfo(10));
        map.put(79, new NumericParseInfo(10));
        map.put(80, new NumericParseInfo(10));
        map.put(81, new NumericParseInfo(10));
        map.put(82, new NumericParseInfo(12));
        map.put(83, new NumericParseInfo(12));
        map.put(84, new NumericParseInfo(12));
        map.put(85, new NumericParseInfo(12));
        map.put(86, new NumericParseInfo(16));
        map.put(87, new NumericParseInfo(16));
        map.put(88, new NumericParseInfo(16));
        map.put(89, new NumericParseInfo(16));
        map.put(90, new LlvarParseInfo());
        map.put(91, new AlphaParseInfo(1));
        map.put(92, new AlphaParseInfo(2));
        map.put(93, new AlphaParseInfo(6));
        map.put(94, new AlphaParseInfo(7));
        map.put(95, new AlphaParseInfo(42));
        map.put(96, new BinaryParseInfo(16));
        map.put(97, new NumericParseInfo(17));
        map.put(98, new AlphaParseInfo(25));

        map.put(99, new LlvarParseInfo());
        map.put(100, new LlvarParseInfo());

        map.put(101, new LlvarParseInfo());
        map.put(102, new LlvarParseInfo());
        map.put(103, new LlvarParseInfo());
        map.put(104, new LllvarParseInfo());

        map.put(120, new LllvarParseInfo());
        map.put(121, new LllvarParseInfo());
        map.put(122, new LllvarParseInfo());
        map.put(123, new LllvarParseInfo());
        map.put(124, new LllvarParseInfo());
        map.put(125, new LllvarParseInfo());
        map.put(126, new LllvarParseInfo());
        map.put(127, new LllvarParseInfo());
        map.put(128, new BinaryParseInfo(8));

        return map;
    }

    private Map<Integer, FieldParseInfo> parseMap0210() {
        return parseMap0200();
    }

    private Map<Integer, FieldParseInfo> parseMap0231() {
        return parseMap0200();
    }

    private Map<Integer, FieldParseInfo> parseMap0800() {
        Map<Integer, FieldParseInfo> map = new HashMap<>();
        map.put(7, new Date10ParseInfo());
        map.put(11, new NumericParseInfo(6));
        map.put(12, new NumericParseInfo(6));
        map.put(13, new NumericParseInfo(4));
        map.put(39, new AlphaParseInfo(2));
        map.put(70, new NumericParseInfo(3));
        return map;
    }

    private Map<Integer, FieldParseInfo> parseMap0810() {
        return parseMap0800();
    }

    @Bean
    public MessageFactory<IsoMessage> messageFactory() {
        log.info("Initializing MessageFactory programmatically (no XML)");
        MessageFactory<IsoMessage> f = new MessageFactory<>();
        f.setCharacterEncoding("UTF-8");
        f.setAssignDate(false);
        f.setUseBinaryBitmap(true);
        f.setIgnoreLastMissingField(true);

        f.addMessageTemplate(base(0x200));
        f.addMessageTemplate(base(0x210));
        f.addMessageTemplate(base(0x0231));
        f.addMessageTemplate(base(0x800));
        f.addMessageTemplate(base(0x810));
        f.addMessageTemplate(base(0x420));
        f.addMessageTemplate(base(0x430));

        f.setParseMap(0x200, parseMap0200());
        f.setParseMap(0x210, parseMap0210());
        f.setParseMap(0x0231, parseMap0231());
        f.setParseMap(0x800, parseMap0800());
        f.setParseMap(0x810, parseMap0810());
        f.setParseMap(0x420, parseMap0200());
        f.setParseMap(0x430, parseMap0200());

        log.info("✓ Programmatic MessageFactory ready (templates: 0200,0210,0231,0800,0810,0420,0430)");
        return f;
    }

    @Bean
    public GenericPackager jposGenericPackager() {
        try (InputStream is = getClass().getResourceAsStream("/packager/fields.xml")) {
            if (is == null) {
                log.warn("jPOS packager resource /packager/fields.xml not found; jPOS features disabled");
                return null;
            }
            GenericPackager packager = new GenericPackager(is);
            log.info("Loaded jPOS GenericPackager from /packager/fields.xml");
            return packager;
        } catch (Exception e) {
            log.warn("Failed to load jPOS GenericPackager: {}", e.getMessage());
            return null;
        }
    }
}
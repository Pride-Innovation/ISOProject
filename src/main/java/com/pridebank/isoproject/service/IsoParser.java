package com.pridebank.isoproject.service;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.MessageFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.text.ParseException;

@Service
@RequiredArgsConstructor
@Slf4j
public class IsoParser {

    private final MessageFactory<IsoMessage> messageFactory;

    public IsoMessage parse(byte[] response) throws ParseException, UnsupportedEncodingException {
        if (response == null || response.length == 0) {
            throw new ParseException("Empty ISO message received", 0);
        }
        return messageFactory.parseMessage(response, 0);
    }
}
package com.pridebank.isoproject.service;

import com.pridebank.isoproject.validation.IsoValidator;
import com.solab.iso8583.IsoMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AtmTransactionProcessorTest {

    @Mock
    private IsoToJsonConverter isoToJsonConverter;
    @Mock
    private JsonToIsoConverter jsonToIsoConverter;
    @Mock
    private EsbGatewayService esbGatewayService;
    @Mock
    private com.solab.iso8583.MessageFactory<IsoMessage> messageFactory;
    @Mock
    private IsoMessageBuilder isoMessageBuilder;
    @Mock
    private IsoValidator isoValidator;

    @InjectMocks
    private AtmTransactionProcessor processor;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void processTransaction_valid_forwardsToEsb_and_returnsConvertedIso() throws Exception {
        IsoMessage req = new IsoMessage();
        req.setType(0x200);
        IsoValidator.ValidationResult ok = IsoValidator.ValidationResult.ok();

        when(isoValidator.validate0200(req)).thenReturn(ok);
        when(isoToJsonConverter.convert(req)).thenReturn("{\"fake\":\"req\"}");
        when(esbGatewayService.sendToEsb("{\"fake\":\"req\"}", req)).thenReturn("{\"responseCode\":\"00\",\"approvalCode\":\"ABC123\"}");
        IsoMessage expectedResp = new IsoMessage();
        expectedResp.setType(0x210);
        when(jsonToIsoConverter.convert("{\"responseCode\":\"00\",\"approvalCode\":\"ABC123\"}", req)).thenReturn(expectedResp);

        IsoMessage r = processor.processTransaction(req);
        assertThat(r).isSameAs(expectedResp);
        verify(esbGatewayService).sendToEsb(anyString(), eq(req));
    }

    @Test
    void processTransaction_validationFails_returnsErrorResponseWith30() {
        IsoMessage req = new IsoMessage();
        req.setType(0x200);
        IsoValidator.ValidationResult failed = IsoValidator.ValidationResult.failed(java.util.List.of("Missing field 2"));
        when(isoValidator.validate0200(req)).thenReturn(failed);

        IsoMessage resp = processor.processTransaction(req);
        assertThat(resp).isNotNull();
        // Field 39 should be set to 30 for validation error
        assertThat(resp.hasField(39)).isTrue();
        assertThat(resp.getObjectValue(39).toString()).isEqualTo("30");
    }
}

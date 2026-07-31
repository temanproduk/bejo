package com.bejo.core.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QrisCodeParserTest {

    /** A real QRIS payload, decoded from an actual AstraPay merchant QR. */
    private static final String REAL_QRIS =
            "00020101021126640018ID.CO.ASTRAPAY.WWW011893600822326062900102092606290010303UMI"
            + "51440014ID.CO.QRIS.WWW0215ID10265407789780303UMI5204581453033605802ID"
            + "5906Susilo6007TANGSEL61051541362080704A00163042C15";

    @Test
    void readsMerchantNameAndNmidFromARealPayload() {
        var identity = QrisCodeParser.parse(REAL_QRIS);

        assertThat(identity.merchantName()).isEqualTo("Susilo");     // tag 59
        assertThat(identity.nmid()).isEqualTo("ID1026540778978");    // tag 51 -> sub-tag 02
        assertThat(identity.acquirer()).isEqualTo("ID.CO.ASTRAPAY.WWW");  // tag 26 -> sub-tag 00
        assertThat(identity.merchantCity()).isEqualTo("TANGSEL");    // tag 60
    }

    @Test
    void theAcquirerIsTheTemplateThatIsNotTheDomesticQrisSwitch() {
        var identity = QrisCodeParser.parse(REAL_QRIS);

        // ID.CO.QRIS.WWW is the national switch, never the acquirer
        assertThat(identity.acquirer()).isNotEqualTo("ID.CO.QRIS.WWW");
        assertThat(identity.acquirer()).isEqualTo("ID.CO.ASTRAPAY.WWW");
    }

    @Test
    void acquirerIsNullWhenTheCodeCarriesOnlyTheDomesticTemplate() {
        String onlyDomestic = "000201" + "5137" + "0014ID.CO.QRIS.WWW0215ID9999999999999"
                + "5905Kopi9" + "63041234";
        var identity = QrisCodeParser.parse(onlyDomestic);

        assertThat(identity.nmid()).isEqualTo("ID9999999999999");
        assertThat(identity.acquirer()).isNull();
    }

    @Test
    void findsTheNmidByGuidNotByTagPosition() {
        // Same data with the QRIS domestic template moved from tag 51 to tag 27 — an acquirer
        // ordering its templates differently must still yield the NMID.
        String moved = "000201" + "2737" + "0014ID.CO.QRIS.WWW0215ID9999999999999"
                + "5905Kopi9" + "63041234";
        var identity = QrisCodeParser.parse(moved);

        assertThat(identity.nmid()).isEqualTo("ID9999999999999");
        assertThat(identity.merchantName()).isEqualTo("Kopi9");
    }

    @Test
    void ignoresANonQrisTemplateWithItsOwnSubTag02() {
        // Tag 26 here is the acquirer's own template and also has a sub-tag 02; only the
        // ID.CO.QRIS.WWW one supplies the NMID.
        var identity = QrisCodeParser.parse(REAL_QRIS);

        assertThat(identity.nmid()).isNotEqualTo("260629001");   // tag 26's sub-tag 02
        assertThat(identity.nmid()).isEqualTo("ID1026540778978");
    }

    @Test
    void returnsEmptyFieldsRatherThanThrowingOnUnreadableInput() {
        // Codes are accepted even with a failing CRC, so parsing must never block an upload
        assertThat(QrisCodeParser.parse(null).merchantName()).isNull();
        assertThat(QrisCodeParser.parse("").nmid()).isNull();
        assertThat(QrisCodeParser.parse("total garbage").nmid()).isNull();
        assertThat(QrisCodeParser.parse("total garbage").acquirer()).isNull();
        assertThat(QrisCodeParser.parse("00XX01").merchantName()).isNull();      // length not numeric
        assertThat(QrisCodeParser.parse("5999Susilo").merchantName()).isNull();  // truncated value
    }

    @Test
    void readsWhatItCanFromAPartiallyMalformedCode() {
        // Merchant name precedes the damage, so it is still recovered
        var identity = QrisCodeParser.parse("000201" + "5906Susilo" + "60XXbroken");

        assertThat(identity.merchantName()).isEqualTo("Susilo");
        assertThat(identity.merchantCity()).isNull();
    }

    @Test
    void absentFieldsAreNullNotBlank() {
        var identity = QrisCodeParser.parse("000201" + "5900" + "63041234");   // empty merchant name

        assertThat(identity.merchantName()).isNull();
        assertThat(identity.nmid()).isNull();
    }

    @Test
    void aValueTooLongForItsColumnIsDroppedNotStored() {
        // An EMV length prefix is 2 digits, so a value can reach 99 chars — longer than the
        // nmid column (50). A real NMID is 15 chars, so an over-long one is not an NMID: storing
        // it would fail the insert, and truncating it would caption the QR with a wrong id.
        String longNmid = "N".repeat(70);
        String tmpl = "0014ID.CO.QRIS.WWW" + "02" + String.format("%02d", longNmid.length()) + longNmid;
        String code = "000201" + "51" + String.format("%02d", tmpl.length()) + tmpl
                + "5906Susilo" + "63041234";

        var identity = QrisCodeParser.parse(code);

        assertThat(identity.nmid()).isNull();                    // dropped, not truncated
        assertThat(identity.merchantName()).isEqualTo("Susilo"); // the rest still parses
    }

    @Test
    void aNormalLengthNmidIsKept() {
        var identity = QrisCodeParser.parse(REAL_QRIS);
        assertThat(identity.nmid()).isEqualTo("ID1026540778978");
        assertThat(identity.nmid().length()).isLessThanOrEqualTo(50);
    }
}

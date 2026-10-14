package com.bejo.voice.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class IndonesianAmountParserTest {

    private static long parse(String heard) {
        return IndonesianAmountParser.parse(heard).orElseThrow(() -> new AssertionError("no amount in: " + heard));
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            // spoken words
            "dua puluh lima ribu | 25000",
            "seratus lima puluh ribu rupiah | 150000",
            "satu juta dua ratus ribu | 1200000",
            "dua juta lima ratus ribu | 2500000",
            "lima belas ribu lima ratus | 15500",
            "dua puluh lima ribu lima ratus rupiah | 25500",
            "sembilan puluh sembilan ribu | 99000",
            "delapan belas ribu | 18000",
            "tiga ratus ribu | 300000",
            "dua ratus lima puluh | 250",
            "dua belas | 12",
            "dua ribu lima ratus | 2500",
            "satu miliar | 1000000000",
            // the "se-" forms
            "seribu | 1000",
            "seribu lima ratus | 1500",
            "seratus | 100",
            "seratus ribu | 100000",
            "sepuluh ribu | 10000",
            "sebelas ribu | 11000",
            "sejuta | 1000000",
            "sejuta lima ratus ribu | 1500000",
            // halves and decimals
            "dua setengah juta | 2500000",
            "setengah juta | 500000",
            "dua ribu setengah | 2500",
            "dua koma lima juta | 2500000",
            "2,5 juta | 2500000",
            "2.5 juta | 2500000",
            "1,5jt | 1500000",
            // digits
            "25000 | 25000",
            "25.000 | 25000",
            "25,000 | 25000",
            "Rp 25.000 | 25000",
            "Rp25.000,- | 25000",
            "1.500.000 | 1500000",
            "25 | 25",
            // digits with a unit
            "25 ribu | 25000",
            "25rb | 25000",
            "25k | 25000",
            "150 ribu | 150000",
            "2 juta 500 ribu | 2500000",
            "20 ribu 500 | 20500",
            // slang
            "goceng | 5000",
            "ceban | 10000",
            "noban | 20000",
            "gocap | 50000",
            "seceng | 1000",
            "ceceng | 1000",
            "noceng | 2000",
            "goban | 50000",
            "gopek | 500",
            "dua ceban | 20000",
            "lima goceng | 25000",
            // talk around the number
            "tolong catat dua puluh ribu rupiah ya | 20000",
            "bayarnya sebesar 45 ribu | 45000",
    })
    void understandsWhatWasSaid(String heard, long expected) {
        assertThat(parse(heard.trim())).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" has no usable amount")
    @ValueSource(strings = {"", "   ", "halo", "tolong catat transaksi", "nol", "0", "kosong", "nol rupiah"})
    void givesNothingWhenThereIsNoAmount(String heard) {
        assertThat(IndonesianAmountParser.parse(heard)).isEmpty();
    }

    @Test
    void doesNotGuessTheAmbiguousSlangCepek() {
        // Sources disagree: Rp100 or Rp100.000. Better to ask again than to fill in the wrong amount.
        assertThat(IndonesianAmountParser.parse("cepek")).isEmpty();
        assertThat(IndonesianAmountParser.parse("tolong catat cepek")).isEmpty();
    }

    @Test
    void givesNothingForNull() {
        assertThat(IndonesianAmountParser.parse(null)).isEmpty();
    }

    @Test
    void refusesAnAmountTheAppCouldNotHold() {
        assertThat(IndonesianAmountParser.parse("999999999 triliun")).isEmpty();
    }
}

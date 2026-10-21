package com.andrewcorp.webwallet.tax.declaration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import com.andrewcorp.webwallet.common.util.Zones;
import com.andrewcorp.webwallet.currency.ExchangeRate;
import com.andrewcorp.webwallet.currency.ExchangeRateRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The declarations against the schemas of the State Tax Service (src/test/resources/tax/xsd, from its reporting
 * software package СКЗ 1.34): every file must validate, as the cabinet rejects any other.
 */
class DeclarationTest extends AbstractIntegrationTest {

    private static final Charset CP1251 = Charset.forName("windows-1251");

    @Autowired
    private ExchangeRateRepository rates;

    private String auth;

    @BeforeEach
    void setUp() throws Exception {
        auth = loginNewUser();
        TestClock.INSTANCE.set(LocalDate.of(2030, 12, 31)
                .atTime(LocalTime.NOON)
                .atZone(Zones.KYIV)
                .toInstant());
        rates.save(new ExchangeRate("USD", LocalDate.of(2028, 1, 1), new BigDecimal("30")));
        rates.save(new ExchangeRate("USD", LocalDate.of(2030, 1, 1), new BigDecimal("40")));
    }

    private void profile(boolean withKved) throws Exception {
        Map<String, Object> profile = new HashMap<>(Map.of(
                "tin", "1234567890",
                "name", "Коваль Олена Петрівна",
                "regionCode", 26,
                "districtCode", 50,
                "taxOffice", "ГУ ДПС у м. Києві",
                "city", "м. Київ",
                "street", "вул. Хрещатик",
                "building", "1",
                "zip", "01001",
                "email", "olena@example.com"));
        if (withKved) {
            profile.put("kvedCode", "62.01");
            profile.put("kvedName", "Комп’ютерне програмування");
        }
        json(putJson("/api/v1/taxes/profile", profile, auth), 200);
    }

    private Long account(String name, String type, String currency) throws Exception {
        return createAccount(auth, name, type, currency, 0);
    }

    private MockHttpServletResponse download(String url) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", auth))
                .andReturn()
                .getResponse();
    }

    private static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                files.put(entry.getName(), new String(in.readAllBytes(), CP1251));
            }
        }
        return files;
    }

    /** Validates the file against the schema it names, the way the tax service does. */
    private static void assertValid(String xml, String form) throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        // The tables of the forms allow more rows than the default limit of the parser
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, false);
        var schema = factory.newSchema(DeclarationTest.class.getResource("/tax/xsd/" + form + ".xsd"));
        schema.newValidator().validate(new StreamSource(new ByteArrayInputStream(xml.getBytes(CP1251))));
    }

    @Test
    void writesTheDeclarationOfTheSoleProprietor() throws Exception {
        Long fop = account("ФОП", "FOP", "UAH");
        for (String date : new String[] {"2030-02-10", "2030-05-10", "2030-11-10"}) {
            json(
                    postJson(
                            "/api/v1/transactions",
                            Map.of("type", "INCOME", "date", date, "accountId", fop, "amount", 10000),
                            auth),
                    201);
        }
        // Nothing to write without the details of the taxpayer, nor without the activity
        assertThat(download("/api/v1/taxes/declarations/fop/2030/1").getStatus())
                .isEqualTo(400);
        json(get("/api/v1/taxes/profile").header("Authorization", auth), 404);
        profile(false);
        assertThat(download("/api/v1/taxes/declarations/fop/2030/1").getStatus())
                .isEqualTo(400);
        profile(true);
        assertThat(json(get("/api/v1/taxes/profile").header("Authorization", auth), 200))
                .containsEntry("tin", "1234567890");

        MockHttpServletResponse second = download("/api/v1/taxes/declarations/fop/2030/2");
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(second.getContentType()).startsWith("application/xml");
        String name = "26501234567890F0103309100000000130620302650.xml";
        assertThat(second.getHeader("Content-Disposition")).contains(name);
        String half = new String(second.getContentAsByteArray(), CP1251);
        assertValid(half, "F0103309");
        // From the start of the year: 20 000 of income, 1 000 of tax of which 500 was due for the first quarter
        assertThat(half)
                .startsWith("<?xml version=\"1.0\" encoding=\"windows-1251\"?>")
                .contains("<HHY>1</HHY>")
                .contains("<R006G3>20000.00</R006G3>")
                .contains("<R011G3>1000.00</R011G3>")
                .contains("<R013G3>500.00</R013G3>")
                .contains("<R0141G3>500.00</R0141G3>")
                .contains("<R023G3>200.00</R023G3>")
                .contains("<R025G3>100.00</R025G3>")
                .contains("<HNAME>Коваль Олена Петрівна</HNAME>")
                .doesNotContain("R021G3");

        // The fourth quarter brings the social contribution of the year in an appendix
        MockHttpServletResponse year = download("/api/v1/taxes/declarations/fop/2030/4");
        assertThat(year.getContentType()).isEqualTo("application/zip");
        Map<String, String> files = unzip(year.getContentAsByteArray());
        assertThat(files).hasSize(2);
        String main = files.get("26501234567890F0103309100000000151220302650.xml");
        String esv = files.get("26501234567890F0133109100000000151220302650.xml");
        assertValid(main, "F0103309");
        assertValid(esv, "F0133109");
        assertThat(main)
                .contains("<HY>1</HY>")
                .contains("<R006G3>30000.00</R006G3>")
                .contains("<R021G3>22828.08</R021G3>")
                .contains("<HD1>1</HD1>")
                .contains("<FILENAME>26501234567890F0133109100000000151220302650.xml</FILENAME>");
        assertThat(esv)
                .contains("<R0912G4>1902.34</R0912G4>")
                .contains("<R09G4>22828.08</R09G4>")
                .contains("<HKVED>62.01</HKVED>")
                .contains("<FILENAME>26501234567890F0103309100000000151220302650.xml</FILENAME>");
    }

    @Test
    void writesTheYearlyDeclarationWithTheInvestmentProfit() throws Exception {
        profile(false);
        Long broker = account("Broker", "INVESTMENT", "USD");
        Long stock = id(json(
                postJson(
                        "/api/v1/investments/instruments",
                        Map.of(
                                "symbol",
                                "ACME",
                                "name",
                                "Acme",
                                "type",
                                "STOCK",
                                "currency",
                                "USD",
                                "valuation",
                                "MANUAL",
                                "manualPrice",
                                100),
                        auth),
                201));
        Map<String, Object> trade = new HashMap<>(Map.of("accountId", broker, "instrumentId", stock));
        trade.putAll(Map.of("type", "BUY", "date", "2028-06-01", "quantity", 10, "price", 100));
        json(postJson("/api/v1/investments/operations", new HashMap<>(trade), auth), 201);
        trade.putAll(Map.of("type", "SELL", "date", "2030-03-01", "quantity", 6, "price", 110));
        json(postJson("/api/v1/investments/operations", new HashMap<>(trade), auth), 201);
        Long dividend = id(json(
                postJson(
                        "/api/v1/investments/operations",
                        Map.of(
                                "type",
                                "DIVIDEND",
                                "accountId",
                                broker,
                                "instrumentId",
                                stock,
                                "date",
                                "2030-06-01",
                                "amount",
                                10,
                                "tax",
                                1.5),
                        auth),
                201));
        json(putJson("/api/v1/taxes/investments/dividends/" + dividend, Map.of("document", true), auth), 204);

        MockHttpServletResponse response = download("/api/v1/taxes/declarations/property/2030");
        assertThat(response.getStatus()).isEqualTo(200);
        Map<String, String> files = unzip(response.getContentAsByteArray());
        String main = files.get("26501234567890F0100215100000000151220302650.xml");
        String f1 = files.get("26501234567890F0121215100000000151220302650.xml");
        assertValid(main, "F0100215");
        assertValid(f1, "F0121215");
        // 660 USD at 40 against 600 USD bought at 30: 8 400 of profit; 10 USD of dividends at 40
        assertThat(f1)
                .contains("<T1RXXXXG4 ROWNUM=\"1\">26400.00</T1RXXXXG4>")
                .contains("<T1RXXXXG5 ROWNUM=\"1\">18000.00</T1RXXXXG5>")
                .contains("<R031G6>8400.00</R031G6>")
                .contains("<R042G6>1512.00</R042G6>")
                .contains("<R052G6>420.00</R052G6>");
        assertThat(main)
                .contains("<R0108G3>8400.00</R0108G3>")
                .contains("<R01010G2S>США, долар США</R01010G2S>")
                .contains("<R01010G3>400.00</R01010G3>")
                .contains("<R01010G6>72.00</R01010G6>")
                .contains("<R013G3>1584.00</R013G3>")
                // The 60 withheld in the USA with its document
                .contains("<R018G3>60.00</R018G3>")
                .contains("<R0201G3>1524.00</R0201G3>")
                .contains("<R0211G3>440.00</R0211G3>")
                .contains("<HD1>1</HD1>");
    }
}

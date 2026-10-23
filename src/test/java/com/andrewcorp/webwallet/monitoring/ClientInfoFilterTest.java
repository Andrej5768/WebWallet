package com.andrewcorp.webwallet.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.common.web.ClientInfo;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

class ClientInfoFilterTest extends AbstractIntegrationTest {

    @Test
    void anApiRequestCarriesItsClientAndLeavesNothingInTheLoggingContext() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/options")
                        .header(ClientInfo.CLIENT_HEADER, "android")
                        .header(ClientInfo.VERSION_HEADER, "1020300"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getRequest().getAttribute(ClientInfo.REQUEST_ATTRIBUTE))
                .isEqualTo(new ClientInfo(ClientInfo.Kind.ANDROID, 1020300));
        assertThat(MDC.get(ClientInfoFilter.MDC_CLIENT)).isNull();
        assertThat(MDC.get(ClientInfoFilter.MDC_APP_VERSION)).isNull();
    }

    @Test
    void aWebRequestIsMarkedAsSuch() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/options"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getRequest().getAttribute(ClientInfo.REQUEST_ATTRIBUTE))
                .isSameAs(ClientInfo.WEB);
    }

    @Test
    void theLoggingContextNamesTheAppWhileTheRequestRuns() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        request.addHeader(ClientInfo.CLIENT_HEADER, "android");
        request.addHeader(ClientInfo.VERSION_HEADER, "7");
        request.addHeader("User-Agent", "WebWallet-Android/1.0.0 (7; Android 14)");
        Map<String, String> seen = new HashMap<>();
        MockFilterChain chain = new MockFilterChain(
                new jakarta.servlet.http.HttpServlet() {}, (req, res, next) -> seen.putAll(MDC.getCopyOfContextMap()));

        new ClientInfoFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen)
                .containsEntry(ClientInfoFilter.MDC_CLIENT, "android")
                .containsEntry(ClientInfoFilter.MDC_APP_VERSION, "7")
                .containsEntry(ClientInfoFilter.MDC_USER_AGENT, "WebWallet-Android/1.0.0 (7; Android 14)");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void theUserAgentOfABrowserIsNotLogged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        request.addHeader("User-Agent", "Mozilla/5.0");
        Map<String, String> seen = new HashMap<>();
        MockFilterChain chain = new MockFilterChain(
                new jakarta.servlet.http.HttpServlet() {}, (req, res, next) -> seen.putAll(MDC.getCopyOfContextMap()));

        new ClientInfoFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen).containsEntry(ClientInfoFilter.MDC_CLIENT, "web");
        assertThat(seen).doesNotContainKeys(ClientInfoFilter.MDC_APP_VERSION, ClientInfoFilter.MDC_USER_AGENT);
    }
}

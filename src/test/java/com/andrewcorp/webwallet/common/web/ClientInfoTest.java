package com.andrewcorp.webwallet.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class ClientInfoTest {

    @Test
    void withoutHeadersTheClientIsTheWeb() {
        ClientInfo client = ClientInfo.of(new MockHttpServletRequest());

        assertThat(client).isSameAs(ClientInfo.WEB);
        assertThat(client.nativeApp()).isFalse();
        assertThat(client.logName()).isEqualTo("web");
    }

    @ParameterizedTest
    @ValueSource(strings = {"android", "ANDROID", " Android "})
    void theKindIsReadIgnoringCaseAndSpaces(String header) {
        ClientInfo client = ClientInfo.of(request(header, "1020300"));

        assertThat(client.kind()).isEqualTo(ClientInfo.Kind.ANDROID);
        assertThat(client.appVersion()).isEqualTo(1020300);
        assertThat(client.nativeApp()).isTrue();
        assertThat(client.logName()).isEqualTo("android");
    }

    @Test
    void iosIsAKnownClient() {
        assertThat(ClientInfo.of(request("ios", "7")).kind()).isEqualTo(ClientInfo.Kind.IOS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"windows", "web", ""})
    void anUnknownKindIsTheWebWithoutAVersion(String header) {
        ClientInfo client = ClientInfo.of(request(header, "5"));

        assertThat(client.kind()).isEqualTo(ClientInfo.Kind.WEB);
        assertThat(client.appVersion()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-1", "0", "", "1.2.3", "123456789012345678901"})
    void aVersionThatIsNotAPositiveNumberIsIgnored(String version) {
        assertThat(ClientInfo.of(request("android", version)).appVersion()).isNull();
    }

    @Test
    void theVersionIsAbsentWhenTheHeaderIs() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientInfo.CLIENT_HEADER, "android");

        assertThat(ClientInfo.of(request).appVersion()).isNull();
    }

    @Test
    void currentPrefersTheClientTheFilterStoredOnTheRequest() {
        MockHttpServletRequest request = request("android", "3");
        ClientInfo stored = new ClientInfo(ClientInfo.Kind.IOS, 9);
        request.setAttribute(ClientInfo.REQUEST_ATTRIBUTE, stored);

        assertThat(ClientInfo.current(request)).isSameAs(stored);
        assertThat(ClientInfo.current(request("android", "3")).appVersion()).isEqualTo(3);
    }

    @Test
    void theResolverGivesControllersTheClientOfTheRequest() throws Exception {
        ClientInfoResolver resolver = new ClientInfoResolver();
        MethodParameter parameter = new MethodParameter(
                ClientInfoTest.class.getDeclaredMethod("controllerMethod", ClientInfo.class, String.class), 0);
        MethodParameter other = new MethodParameter(
                ClientInfoTest.class.getDeclaredMethod("controllerMethod", ClientInfo.class, String.class), 1);

        assertThat(resolver.supportsParameter(parameter)).isTrue();
        assertThat(resolver.supportsParameter(other)).isFalse();
        ClientInfo resolved =
                resolver.resolveArgument(parameter, null, new ServletWebRequest(request("android", "42")), null);
        assertThat(resolved).isEqualTo(new ClientInfo(ClientInfo.Kind.ANDROID, 42));
    }

    @SuppressWarnings("unused")
    void controllerMethod(ClientInfo client, String other) {}

    private static MockHttpServletRequest request(String kind, String version) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientInfo.CLIENT_HEADER, kind);
        request.addHeader(ClientInfo.VERSION_HEADER, version);
        return request;
    }
}

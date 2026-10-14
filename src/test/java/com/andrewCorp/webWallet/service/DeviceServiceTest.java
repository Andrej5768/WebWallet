package com.andrewCorp.webWallet.service;

import com.andrewCorp.webWallet.persistence.dao.DeviceMetadataRepository;
import com.andrewCorp.webWallet.persistence.model.DeviceMetadata;
import com.andrewCorp.webWallet.persistence.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import ua_parser.Parser;

import java.util.Date;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceServiceTest {

    private static final String CHROME_ON_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final DeviceMetadataRepository repository = mock(DeviceMetadataRepository.class);
    private DeviceService deviceService;
    private User user;

    @BeforeEach
    void setUp() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("message.login.notification.deviceDetails", Locale.ENGLISH, "Device details:");
        messages.addMessage("message.login.notification.ip", Locale.ENGLISH, "Location:");
        deviceService = new DeviceService(repository, new Parser(), messages);

        user = new User();
        user.setId(7L);
        user.setEmail("user@test.com");
    }

    private MockHttpServletRequest request(String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("user-agent", userAgent);
        request.setRemoteAddr("192.168.0.1");
        request.addPreferredLocale(Locale.ENGLISH);
        return request;
    }

    @Test
    void unknownDeviceIsStored() throws Exception {
        when(repository.findByUserId(7L)).thenReturn(List.of());

        deviceService.verifyDevice(user, request(CHROME_ON_WINDOWS));

        ArgumentCaptor<DeviceMetadata> captor = ArgumentCaptor.forClass(DeviceMetadata.class);
        verify(repository).save(captor.capture());
        DeviceMetadata saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getDeviceDetails()).startsWith("Chrome 120.0").contains("Windows");
        assertThat(saved.getLastLoggedIn()).isNotNull();
    }

    @Test
    void knownDeviceGetsLastLoginUpdated() throws Exception {
        DeviceMetadata otherDevice = new DeviceMetadata();
        otherDevice.setDeviceDetails("Firefox 1.0 - Linux null.null");
        DeviceMetadata known = new DeviceMetadata();
        known.setUserId(7L);
        // The same details the service computes for this user agent
        known.setDeviceDetails(detailsFor(CHROME_ON_WINDOWS));
        known.setLastLoggedIn(new Date(0));
        when(repository.findByUserId(7L)).thenReturn(List.of(otherDevice, known));

        MockHttpServletRequest request = request(CHROME_ON_WINDOWS);
        request.addHeader("x-forwarded-for", "1.2.3.4, 5.6.7.8");
        deviceService.verifyDevice(user, request);

        verify(repository).save(known);
        assertThat(known.getLastLoggedIn()).isAfter(new Date(0));
    }

    private String detailsFor(String userAgent) throws Exception {
        DeviceMetadataRepository captureRepository = mock(DeviceMetadataRepository.class);
        when(captureRepository.findByUserId(7L)).thenReturn(List.of());
        StaticMessageSource messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        new DeviceService(captureRepository, new Parser(), messages).verifyDevice(user, request(userAgent));
        ArgumentCaptor<DeviceMetadata> captor = ArgumentCaptor.forClass(DeviceMetadata.class);
        verify(captureRepository).save(captor.capture());
        return captor.getValue().getDeviceDetails();
    }

    @Test
    void missingTranslationFallsBackToEnglish() throws Exception {
        when(repository.findByUserId(7L)).thenReturn(List.of());
        MockHttpServletRequest request = request(CHROME_ON_WINDOWS);
        request.addPreferredLocale(Locale.GERMAN);

        deviceService.verifyDevice(user, request);

        verify(repository).save(org.mockito.ArgumentMatchers.any(DeviceMetadata.class));
    }
}

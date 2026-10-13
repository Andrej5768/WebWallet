package com.andrewCorp.webWallet.service;

import com.andrewCorp.webWallet.persistence.dao.DeviceMetadataRepository;
import com.andrewCorp.webWallet.persistence.model.DeviceMetadata;
import com.andrewCorp.webWallet.persistence.model.User;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;
import ua_parser.Client;
import ua_parser.Parser;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static java.util.Objects.nonNull;

@Component
public class DeviceService {

    private static final String UNKNOWN = "UNKNOWN";

//    @Value("${support.email}")
//    private String from;

    private DeviceMetadataRepository deviceMetadataRepository;
    private Parser parser;
//    private JavaMailSender mailSender;
    private MessageSource messages;

    public DeviceService(DeviceMetadataRepository deviceMetadataRepository,
                         Parser parser,
//                         JavaMailSender mailSender,
                         MessageSource messages) {
        this.deviceMetadataRepository = deviceMetadataRepository;
        this.parser = parser;
//        this.mailSender = mailSender;
        this.messages = messages;
    }

    public void verifyDevice(User user, HttpServletRequest request) throws IOException {

        String ip = extractIp(request);

        String deviceDetails = getDeviceDetails(request.getHeader("user-agent"));

        DeviceMetadata existingDevice = findExistingDevice(user.getId(), deviceDetails);

        if (Objects.isNull(existingDevice)) {
            unknownDeviceNotification(deviceDetails, ip, user.getEmail(), request.getLocale());

            DeviceMetadata deviceMetadata = new DeviceMetadata();
            deviceMetadata.setUserId(user.getId());
            deviceMetadata.setDeviceDetails(deviceDetails);
            deviceMetadata.setLastLoggedIn(new Date());
            deviceMetadataRepository.save(deviceMetadata);
        } else {
            existingDevice.setLastLoggedIn(new Date());
            deviceMetadataRepository.save(existingDevice);
        }

    }

    private String extractIp(HttpServletRequest request) {
        String clientIp;
        String clientXForwardedForIp = request.getHeader("x-forwarded-for");
        if (nonNull(clientXForwardedForIp)) {
            clientIp = parseXForwardedHeader(clientXForwardedForIp);
        } else {
            clientIp = request.getRemoteAddr();
        }

        return clientIp;
    }

    private String parseXForwardedHeader(String header) {
        return header.split(" *, *")[0];
    }

    private String getDeviceDetails(String userAgent) {
        String deviceDetails = UNKNOWN;

        Client client = parser.parse(userAgent);
        if (Objects.nonNull(client)) {
            deviceDetails = client.userAgent.family + " " + client.userAgent.major + "." + client.userAgent.minor +
                    " - " + client.os.family + " " + client.os.major + "." + client.os.minor;
        }

        return deviceDetails;
    }

    private DeviceMetadata findExistingDevice(Long userId, String deviceDetails) {

        List<DeviceMetadata> knownDevices = deviceMetadataRepository.findByUserId(userId);

        for (DeviceMetadata existingDevice : knownDevices) {
            if (existingDevice.getDeviceDetails().equals(deviceDetails)) {
                return existingDevice;
            }
        }

        return null;
    }

    private void unknownDeviceNotification(String deviceDetails, String ip, String email, Locale locale) {
        final String subject = "New Login Notification";
        final SimpleMailMessage notification = new SimpleMailMessage();
        notification.setTo(email);
        notification.setSubject(subject);

        String text = getMessage("message.login.notification.deviceDetails", locale) +
                " " + deviceDetails +
                "\n" +
                    getMessage("message.login.notification.ip", locale) +
                " " + ip;

//        notification.setText(text);
//        notification.setFrom(from);

//        mailSender.send(notification);
    }

    private String getMessage(String code, Locale locale) {
        try {
            return messages.getMessage(code, null, locale);

        } catch (NoSuchMessageException ex) {
            return messages.getMessage(code, null, Locale.ENGLISH);
        }
    }

}

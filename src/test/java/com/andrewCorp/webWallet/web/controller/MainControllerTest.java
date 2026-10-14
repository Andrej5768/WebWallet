package com.andrewCorp.webWallet.web.controller;

import com.andrewCorp.webWallet.AbstractIntegrationTest;
import com.andrewCorp.webWallet.persistence.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

import java.util.Locale;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Tests for MainController and the small page controllers (UserController, RoleHierarchyController).
 */
class MainControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MessageSource messages;

    @Test
    void indexIsPublicAndShowsMessage() throws Exception {
        mockMvc.perform(get("/index").param("messageKey", "message.badCredentials").param("error", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attribute("message", messages.getMessage("message.badCredentials", null, Locale.ENGLISH)))
                .andExpect(model().attribute("error", "true"));
    }

    @Test
    void homepageShowsWalletOfCurrentUser() throws Exception {
        User user = registerUser();

        mockMvc.perform(get("/homepage").with(as(user)).param("messageKey", "message.accountVerified").param("error", "x"))
                .andExpect(status().isOk())
                .andExpect(view().name("homepage"))
                .andExpect(model().attributeExists("accounts", "transaction", "message", "error"));
    }

    @Test
    void debugShowsEmailOfCurrentUser() throws Exception {
        User user = registerUser();

        mockMvc.perform(get("/debug").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(view().name("debug"))
                .andExpect(model().attribute("debug", user.getEmail()));
    }

    @Test
    void loggedUsers() throws Exception {
        mockMvc.perform(get("/loggedUsers").with(as(registerUser())))
                .andExpect(status().isOk())
                .andExpect(view().name("users"))
                .andExpect(model().attributeExists("users"));
    }

    @Test
    void loggedUsersFromSessionRegistry() throws Exception {
        mockMvc.perform(get("/loggedUsersFromSessionRegistry").with(as(registerUser())))
                .andExpect(status().isOk())
                .andExpect(view().name("users"))
                .andExpect(model().attributeExists("users"));
    }

    @Test
    void roleHierarchy() throws Exception {
        mockMvc.perform(get("/roleHierarchy").with(as(registerUser())))
                .andExpect(status().isOk())
                .andExpect(view().name("roleHierarchy"))
                .andExpect(model().attributeExists("adminMessage", "staffMessage", "userMessage"));
    }
}

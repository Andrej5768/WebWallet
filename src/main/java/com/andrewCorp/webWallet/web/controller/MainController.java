package com.andrewCorp.webWallet.web.controller;

import com.andrewCorp.webWallet.persistence.model.Account;
import com.andrewCorp.webWallet.persistence.model.User;
import com.andrewCorp.webWallet.persistence.model.Wallet;
import com.andrewCorp.webWallet.service.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Controller
public class MainController {

    @Autowired
    private IUserService userService;

    @Autowired
    private MessageSource messages;

    // TODO refractor or remove
    @GetMapping("/index")
    public ModelAndView login(final HttpServletRequest request, final ModelMap model,
                              @RequestParam("messageKey") final Optional<String> messageKey,
                              @RequestParam("error") final Optional<String> error) {
        Locale locale = request.getLocale();
        model.addAttribute("lang", locale.getLanguage());
        messageKey.ifPresent(key -> {
                    String message = messages.getMessage(key, null, locale);
                    model.addAttribute("message", message);
                }
        );

        error.ifPresent(e -> model.addAttribute("error", e));

        return new ModelAndView("index", model);
    }

    @GetMapping("/homepage")
    public ModelAndView homepage(final HttpServletRequest request, final ModelMap model,
                                 @RequestParam("messageKey") final Optional<String> messageKey,
                                 @RequestParam("error") final Optional<String> error) {
        Locale locale = request.getLocale();
        model.addAttribute("lang", locale.getLanguage());
        messageKey.ifPresent(key -> {
                    String message = messages.getMessage(key, null, locale);
                    model.addAttribute("message", message);
                }
        );
        model.addAttribute("accounts", getUserFromContext().getWallet().getAccounts());
        model.addAttribute("transaction", getUserFromContext().getWallet().getTransactions());
        error.ifPresent(e -> model.addAttribute("error", e));

        return new ModelAndView("homepage", model);
    }

    @GetMapping("/debug")
    public String debugPost(Model model) {
        final User user = getUserFromContext();

        model.addAttribute("debug", user.getEmail());

        return "debug";
    }

    private User getUserFromContext() {
        return userService.findUserByEmail(((User) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getEmail());
    }
}

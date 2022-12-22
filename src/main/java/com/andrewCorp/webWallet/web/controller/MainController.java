package com.andrewCorp.webWallet.web.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Optional;

@Controller
public class MainController {

    @Autowired
    private MessageSource messages;

    @GetMapping("/index")
    public ModelAndView login(final HttpServletRequest request, final ModelMap model,
                              @RequestParam("messageKey" ) final Optional<String> messageKey,
                              @RequestParam("error" ) final Optional<String> error) {
        Locale locale = request.getLocale();
        model.addAttribute("lang", locale.getLanguage());
        messageKey.ifPresent( key -> {
                    String message = messages.getMessage(key, null, locale);
                    model.addAttribute("message", message);
                }
        );

        error.ifPresent( e ->  model.addAttribute("error", e));

        return new ModelAndView("index", model);
    }
}

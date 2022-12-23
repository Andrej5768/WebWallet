package com.andrewCorp.webWallet.web.controller;

import com.andrewCorp.webWallet.persistence.dao.AccountRepository;
import com.andrewCorp.webWallet.persistence.model.Account;
import com.andrewCorp.webWallet.persistence.model.User;
import com.andrewCorp.webWallet.service.IUserService;
import com.andrewCorp.webWallet.web.dto.AccountsCreationDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/")
public class AccountController {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IUserService userService;
//TODO
    @GetMapping("/wallet")
    public String main(@RequestParam(required = false, defaultValue = "") String filter, Model model) {
        final User user = userService.findUserByEmail(((User) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getEmail());
        Iterable<Account> accounts = accountRepository.findAccountsByUserId(user.getId());

//        if (filter != null && !filter.isEmpty()) {
//            messages = messageRepo.findByTag(filter);
//        } else {
//            accounts = accountRepository.findAll();
//        }

        model.addAttribute("accounts", accounts);
        model.addAttribute("filter", filter);

        return "wallet";
    }

    @PostMapping("/account")
    public String add(
            @AuthenticationPrincipal User user,
            @RequestParam String name,
            @RequestParam Map<String, Object> model
    ) throws IOException {
        Account account = new Account(name);

        accountRepository.save(account);

        Iterable<Account> accounts = accountRepository.findAll();

        model.put("accounts", accounts);

        return "account";
    }

    @GetMapping(value = "/all")
    public String showAll(Model model) {
        model.addAttribute("accounts", accountRepository.findAll());

        return "allAccounts";
    }

    @GetMapping(value = "/create")
    public String showCreateForm(Model model) {
        AccountsCreationDto accountsForm = new AccountsCreationDto();

        for (int i = 1; i <= 3; i++) {
            accountsForm.addAccounts(new Account());
        }

        model.addAttribute("form", accountsForm);

        return "createAccountsForm";
    }

    @GetMapping(value = "/edit")
    public String showEditForm(Model model) {
        List<Account> accounts = new ArrayList<>();
        accountRepository.findAll()
            .iterator()
            .forEachRemaining(accounts::add);

        model.addAttribute("form", new AccountsCreationDto(accounts));

        return "editAccountsForm";
    }

    @PostMapping(value = "/saveAll")
    public String saveAccounts(@ModelAttribute AccountsCreationDto form, Model model) {
        accountRepository.saveAll(form.getAccounts());

        model.addAttribute("accounts", accountRepository.findAll());

        return "redirect:/accounts/all";
    }

    @PostMapping(value = "/save")
    public String saveAccounts(@RequestParam String name, @RequestParam BigDecimal amount, Model model) {
        accountRepository.save(new Account(name, amount));

        model.addAttribute("accounts", accountRepository.findAll());

        return "redirect:/accounts/all";
    }
}

package com.andrewCorp.webWallet.web.controller;

import com.andrewCorp.webWallet.persistence.dao.AccountRepository;
import com.andrewCorp.webWallet.persistence.dao.TransactionRepository;
import com.andrewCorp.webWallet.persistence.enums.TransactionType;
import com.andrewCorp.webWallet.persistence.model.Account;
import com.andrewCorp.webWallet.persistence.model.Transaction;
import com.andrewCorp.webWallet.web.dto.AccountsCreationDto;
import com.andrewCorp.webWallet.web.dto.TransactionCreationDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Controller
@RequestMapping("/accounts/transaction")
public class TransactionController {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @PostMapping(value = "/all")
    public String showAllPost(@ModelAttribute Long accountId, Model model) {
        model.addAttribute("transactions", transactionRepository.findTransactionsByAccountId(accountId));
        model.addAttribute("account", accountRepository.findById(accountId));
        model.addAttribute("types", TransactionType.values());

        return "account";
    }

    @GetMapping(value = "/all")
    public String showAll(@RequestParam Long accountId, Model model) {
        model.addAttribute("transactions", transactionRepository.findTransactionsByAccountId(accountId));
        model.addAttribute("account", accountRepository.findById(accountId));
        model.addAttribute("types", TransactionType.values());

        return "account";
    }

    @GetMapping(value = "/create")
    public String showCreateForm(Model model) {
        AccountsCreationDto accountsForm = new AccountsCreationDto();

        for (int i = 1; i <= 3; i++) {
            accountsForm.addAccounts(new Account());
        }

        model.addAttribute("form", accountsForm);

        return "createTransactionsForm";
    }

    @GetMapping(value = "/edit")
    public String showEditForm(Model model) {
        List<Transaction> transactions = new ArrayList<>();
        transactionRepository.findAll()
            .iterator()
            .forEachRemaining(transactions::add);

        model.addAttribute("form", new TransactionCreationDto(transactions));
        model.addAttribute("types", TransactionType.values());

        return "editTransactionsForm";
    }

    @PostMapping(value = "/saveAll")
    public String saveAccounts(@ModelAttribute TransactionCreationDto form, Model model) {
        transactionRepository.saveAll(form.getTransactions());

        model.addAttribute("transactions", transactionRepository.findAll());

        model.addAttribute("types", TransactionType.values());

        return "redirect:/accounts/transaction/all";
    }

    @PostMapping(value = "/save")
    public String saveAccounts(@RequestParam Long accountId,
                               @RequestParam BigDecimal amount,
                               @RequestParam String remark,
                               @RequestParam Date date,
                               @RequestParam TransactionType transactionType,
                               Model model) {
        transactionRepository.save(new Transaction(accountId, amount, remark, date, transactionType));

        model.addAttribute("transactions", transactionRepository.findAll());

        model.addAttribute("types", TransactionType.values());

        return "redirect:/accounts/transaction/all";
    }
}

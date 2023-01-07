package com.andrewCorp.webWallet.web.controller;

import com.andrewCorp.webWallet.persistence.dao.AccountRepository;
import com.andrewCorp.webWallet.persistence.dao.CategoryRepository;
import com.andrewCorp.webWallet.persistence.dao.TransactionRepository;
import com.andrewCorp.webWallet.persistence.dao.WalletRepository;
import com.andrewCorp.webWallet.persistence.enums.TransactionType;
import com.andrewCorp.webWallet.persistence.model.*;
import com.andrewCorp.webWallet.service.IUserService;
import com.andrewCorp.webWallet.web.dto.AccountsCreationDto;
import com.andrewCorp.webWallet.web.dto.CategoryCreationDto;
import com.andrewCorp.webWallet.web.dto.TransactionCreationDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/")
public class WalletController {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private IUserService userService;

    @Autowired
    private CategoryRepository categoryRepository;


//TODO

    @GetMapping(value = "accounts/all")
    public String showAllAccounts(Model model) {

        model.addAttribute("accounts", accountRepository.findAllByWalletId(getWalletFromContext().getId()));

        return "accounts";
    }



    @GetMapping(value = "accounts/create")
    public String showAccountsCreateForm(Model model) {
        AccountsCreationDto accountsForm = new AccountsCreationDto();

        for (int i = 1; i <= 3; i++) {
            accountsForm.addAccounts(new Account());
        }

        model.addAttribute("form", accountsForm);

        return "createAccountsForm";
    }

    @GetMapping(value = "accounts/edit")
    public String showAccountsEditForm(Model model) {
        List<Account> accounts = new ArrayList<>();
        accountRepository.findAllByWalletId(getWalletFromContext().getId())
                .iterator()
                .forEachRemaining(accounts::add);

        model.addAttribute("form", new AccountsCreationDto(accounts));

        return "editAccountsForm";
    }

    @PostMapping(value = "accounts/saveAll")
    public String saveAccounts(@ModelAttribute AccountsCreationDto form, Model model) {
        accountRepository.saveAll(form.getAccounts());

        model.addAttribute("accounts", accountRepository.findAllByWalletId(getWalletFromContext().getId()));

        return "redirect:/accounts/all";
    }

    @PostMapping(value = "accounts/save")
    public String saveAccount(@RequestParam String name, @RequestParam BigDecimal amount, Model model) {
        accountRepository.save(new Account(name, amount, getWalletFromContext()));

        model.addAttribute("accounts", accountRepository.findAllByWalletId(getWalletFromContext().getId()));

        return "redirect:/accounts/all";
    }

    @RequestMapping(value = "/populateDropDownList", method = RequestMethod.GET)
    public String populateList(Model model) {
        List<String> options = new ArrayList<String>();
        options.add("option 1");
        options.add("option 2");
        options.add("option 3");
        model.addAttribute("options", options);
        return "dropDownList/dropDownList.html";
    }

    @GetMapping(value = "transactions/all")
    public String showAllTransactions(Model model) {
        Wallet wallet = getWalletFromContext();
        model.addAttribute("transactions", transactionRepository.findTransactionsByWalletId(wallet.getId()));
        model.addAttribute("accounts", accountRepository.findAllByWalletId(wallet.getId()));
        model.addAttribute("types", TransactionType.values());
        model.addAttribute("categories", wallet.getCategories());

        return "transactions";
    }

    @PostMapping(value = "transactions/all")
    public String showAllTransactionsPost(Model model) {
        Wallet wallet = getWalletFromContext();
        model.addAttribute("transactions", transactionRepository.findTransactionsByWalletId(wallet.getId()));
        model.addAttribute("accounts", accountRepository.findAllByWalletId(wallet.getId()));
        model.addAttribute("types", TransactionType.values());
        model.addAttribute("categories", wallet.getCategories());

        return "transactions";
    }


    @GetMapping(value = "/account/{id}/transactions")
    public String showAccountTransactions(@PathVariable(name = "id") Long id, Model model) {
        Account account = accountRepository.getReferenceById(id);
        model.addAttribute("transactions", transactionRepository.findTransactionsByAccountId(account.getId()));
        model.addAttribute("accounts", accountRepository.findAllByWalletId(account.getWallet().getId()));
        model.addAttribute("types", TransactionType.values());
        model.addAttribute("categories", account.getWallet().getCategories());

        return "transactions";
    }

    @GetMapping(value = "transactions/create")
    public String showTransactionsCreateForm(Model model) {
        AccountsCreationDto accountsForm = new AccountsCreationDto();

        for (int i = 1; i <= 3; i++) {
            accountsForm.addAccounts(new Account());
        }

        model.addAttribute("form", accountsForm);

        return "createTransactionsForm";
    }

    @GetMapping(value = "transactions/edit")
    public String showTransactionsEditForm(Model model) {
        List<Transaction> transactions = new ArrayList<>();
        transactionRepository.findAll()
                .iterator()
                .forEachRemaining(transactions::add);

        model.addAttribute("form", new TransactionCreationDto(transactions));
        model.addAttribute("types", TransactionType.values());

        return "editTransactionsForm";
    }

    @PostMapping(value = "transactions/update/{id}")
    public String updateTransaction(@ModelAttribute Transaction transaction, Model model, @PathVariable(name = "id") Long id) {
        Transaction transactionFromDb = transactionRepository.getReferenceById(id);

        if (transaction.getDate() != null) {
            transactionFromDb.setDate(transaction.getDate());
        }
        if (transaction.getType() != null) {
            transactionFromDb.setType(transaction.getType());
        }
        if (transaction.getAmount() != null) {
            transactionFromDb.setAmount(transaction.getAmount());
        }
        if (transaction.getAccount() != null) {
            transactionFromDb.setAccount(transaction.getAccount());
        }

        transactionRepository.saveAndFlush(transactionFromDb);


        return "redirect:/transactions/all";
    }

    @PostMapping(value = "transactions/saveAll")
    public String saveTransactions(@ModelAttribute TransactionCreationDto form, Model model) {
        transactionRepository.saveAll(form.getTransactions());

        model.addAttribute("transactions", transactionRepository.findAll());

        model.addAttribute("types", TransactionType.values());

        return "redirect:/accounts/transaction/all";
    }


    @PostMapping(value = "/transactions/save")
    public String saveTransaction(
            @RequestParam(value = "date") String date,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "amount") String amount,
            @RequestParam(value = "account") String account,
            @RequestParam(value = "transactionType") String transactionType,
            @RequestParam(value = "remark", required = false) String remark,
            Model model) {
        Transaction transactionFromDB = transactionRepository.save(new Transaction());
        transactionFromDB.setDate(Date.valueOf(date));
        if (category != null) {
//            transactionFromDB.se TODO
        }
        transactionFromDB.setAmount(BigDecimal.valueOf(Double.parseDouble(amount)));
        transactionFromDB.setAccount(accountRepository.findByName(account));
        transactionFromDB.setType(TransactionType.valueOf(transactionType));
        if (remark != null) {
            transactionFromDB.setRemark(remark);
        }
        transactionFromDB.setWallet(getWalletFromContext());

//        ModelAndView modelAndView = new ModelAndView("transactions")

        Wallet wallet = getWalletFromContext();
        model.addAttribute("transactions", transactionRepository.findTransactionsByWalletId(wallet.getId()));
        model.addAttribute("accounts", accountRepository.findAllByWalletId(wallet.getId()));
        model.addAttribute("types", TransactionType.values());
        model.addAttribute("categories", wallet.getCategories());

//        return modelAndView;

        return "redirect:/transactions/all";
//        return "transactions";
    }




//    @PostMapping(value = "transactions/save")
//    public String saveTransaction(@RequestParam Account account,
//                               @RequestParam BigDecimal amount,
//                               @RequestParam String remark,
//                               @RequestParam Date date,
//                               @RequestParam TransactionType transactionType,
//                               Model model) {
//        transactionRepository.save(new Transaction(account.getId(), amount, "debug", date, TransactionType.EXPENSE));
//
//
//        Wallet wallet = getWalletFromContext();
//        model.addAttribute("transactions", transactionRepository.findTransactionsByWalletId(wallet.getId()));
//        model.addAttribute("accounts", accountRepository.findAllByWalletId(wallet.getId()));
//        model.addAttribute("types", TransactionType.values());
//        model.addAttribute("categories", wallet.getCategories());
//
//        return "redirect:/transactions/all";
//    }

//    @PostMapping(value = "transactions/save")
//    public String saveTransaction(@ModelAttribute Transaction transaction, @ModelAttribute Account account, Model model) {
//        Transaction transactionFromDb = transactionRepository.save(new Transaction());
//
//        transactionFromDb.setType(transaction.getType());
//        transactionFromDb.setWallet(getWalletFromContext());
//        transactionFromDb.setAccountId(account.getId());
//        transactionFromDb.setAmount(transaction.getAmount());
//        transactionFromDb.setDate(transaction.getDate());
//
//
//        Wallet wallet = getWalletFromContext();
//        model.addAttribute("transactions", transactionRepository.findTransactionsByWalletId(wallet.getId()));
//        model.addAttribute("accounts", accountRepository.findAllByWalletId(wallet.getId()));
//        model.addAttribute("types", TransactionType.values());
//        model.addAttribute("categories", wallet.getCategories());
//
//        return "redirect:/transactions/all";
//    }


    private Wallet getWalletFromContext() {
        final User user = userService.findUserByEmail(((User) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getEmail());
        return user.getWallet();
    }
}

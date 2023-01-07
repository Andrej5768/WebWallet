package com.andrewCorp.webWallet.web.dto;


import com.andrewCorp.webWallet.persistence.model.Account;
import com.andrewCorp.webWallet.persistence.model.Category;

import java.util.ArrayList;
import java.util.List;

public class CategoryCreationDto {

    //TODO chande or remove

    private final List<String> defaultCategory = new ArrayList<String>() {
        {
            add("Housing");
            add("Transport");
            add("Food");
            add("Utilities");
            add("Insurance");
            add("Healthcare");
            add("Saving");
            add("Food");
        }
    };

    private List<Category> categories;

    public CategoryCreationDto() {
        this.categories = new ArrayList<>();
        for (String categoryName : defaultCategory) {
            Category category = new Category();
            category.setName(categoryName);
            categories.add(category);
        }
    }

    public CategoryCreationDto(List<Category> categories) {
        this.categories = categories;
    }

    public List<Category> getAccounts() {
        return categories;
    }

    public void setAccounts(List<Category> categories) {
        this.categories = categories;
    }

    public void addAccounts(Category category) {
        this.categories.add(category);
    }
}

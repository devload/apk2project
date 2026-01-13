package com.test.obf;

import java.util.ArrayList;
import java.util.List;

public class DataProcessor {

    private List<String> items = new ArrayList<>();

    // [Deobfuscated] d -> addNonEmptyStringToItems * Adds a non-empty string to the 'items' collection
    public void addNonEmptyStringToItems(String nonEmptyInput) {
        if (nonEmptyInput != null && !nonEmptyInput.isEmpty()) {
            items.add(nonEmptyInput);
        }
    }

    // [Deobfuscated] e -> getItemsCount * returns the number of items in a collection
    public int getItemsCount() {
        return items.size();
    }

    // [Deobfuscated] f -> getItemAtIndex * retrieves an item from a list at a specific index if it exists
    public String getItemAtIndex(int index) {
        if (index >= 0 && index < items.size()) {
            return items.get(index);
        }
        return null;
    }

    // [Deobfuscated] g -> clearItemsList * clears the list of items
    public void clearItemsList() {
        items.clear();
    }
}

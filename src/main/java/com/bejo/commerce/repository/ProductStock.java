package com.bejo.commerce.repository;

/** A product's id and current stock, read from the database (see {@code findStockByIdIn}). */
public interface ProductStock {

    Long getId();
    int getStock();
}

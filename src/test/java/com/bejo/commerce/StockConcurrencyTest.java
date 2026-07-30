package com.bejo.commerce;

import com.bejo.commerce.dto.AddTransactionRequestDto;
import com.bejo.commerce.dto.CreateProductRequestDto;
import com.bejo.commerce.dto.TransactionItemRequestDto;
import com.bejo.commerce.repository.ProductRepository;
import com.bejo.commerce.service.ProductService;
import com.bejo.commerce.service.TransactionService;
import com.bejo.core.dto.RegisterRequestDto;
import com.bejo.core.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stock must survive concurrent sales.
 *
 * <p>The services used to read a product's stock, subtract in Java, and save the result. Two
 * simultaneous sales then both read the same starting figure and both wrote their own answer,
 * so one sale silently vanished from inventory and stock could be driven negative. The fix is a
 * conditional {@code UPDATE … WHERE stock >= :qty} that lets the database arbitrate; this test
 * is what actually holds that guarantee in place.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class StockConcurrencyTest {

    private static final String USER = "stock_race_user";
    private static final int STARTING_STOCK = 20;
    private static final int CONCURRENT_SALES = 40;   // twice the stock: half must be refused

    @Autowired UserService userService;
    @Autowired ProductService productService;
    @Autowired TransactionService transactionService;
    @Autowired ProductRepository productRepository;

    @Test
    void concurrentSalesNeverOversellOrLoseAnUpdate() throws Exception {
        userService.register(new RegisterRequestDto(
                USER, "password1", "Race Tester", USER + "@example.com", "+628123456789",
                "Warung Race", null, null, null, null, null, null, null, null, null));
        Long productId = productService.create(USER, new CreateProductRequestDto(
                "RACE-SKU", "Kopi", null, null, 10_000L, null, STARTING_STOCK, null)).id();

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch startGun = new CountDownLatch(1);
        AtomicInteger sold = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        for (int i = 0; i < CONCURRENT_SALES; i++) {
            String idempotencyKey = "race-" + i;
            pool.submit(() -> {
                try {
                    startGun.await();
                    transactionService.create(USER, idempotencyKey, new AddTransactionRequestDto(
                            "MANUAL", LocalDateTime.now(), 10_000L, "Cash", null, null, null, null,
                            List.of(new TransactionItemRequestDto(productId, 1))));
                    sold.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    refused.incrementAndGet();   // out of stock — the correct outcome here
                } catch (Exception e) {
                    // Anything else (deadlock, constraint) would be a real defect; count it as
                    // neither, so the assertions below fail loudly.
                }
            });
        }
        startGun.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        int remaining = productRepository.findById(productId).orElseThrow().getStock();

        // The invariant: stock never goes negative, and every unit is accounted for exactly once
        assertThat(remaining).isNotNegative();
        assertThat(sold.get()).isEqualTo(STARTING_STOCK);
        assertThat(refused.get()).isEqualTo(CONCURRENT_SALES - STARTING_STOCK);
        assertThat(remaining).isZero();
    }
}

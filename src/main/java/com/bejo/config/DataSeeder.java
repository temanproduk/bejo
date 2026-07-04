package com.bejo.config;

import com.bejo.constant.AccountType;
import com.bejo.constant.Acquirer;
import com.bejo.constant.TransactionStatus;
import com.bejo.constant.TransactionType;
import com.bejo.entity.Transaction;
import com.bejo.entity.User;
import com.bejo.entity.UserBank;
import com.bejo.entity.UserQris;
import com.bejo.repository.TransactionRepository;
import com.bejo.repository.UserBankRepository;
import com.bejo.repository.UserQrisRepository;
import com.bejo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    @Value("${app.seed.username:admin}")
    private String seedUsername;

    @Value("${app.seed.password:}")
    private String seedPassword;

    @Value("${app.seed.name:Admin}")
    private String seedName;

    @Value("${app.seed.business-name:My Business}")
    private String seedBusinessName;

    @Value("${app.seed.business-address:}")
    private String seedBusinessAddress;

    @Value("${app.seed.nmid:}")
    private String seedNmid;

    private final UserRepository userRepository;
    private final UserBankRepository userBankRepository;
    private final UserQrisRepository userQrisRepository;
    private final TransactionRepository transactionRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(UserRepository userRepository,
                      UserBankRepository userBankRepository,
                      UserQrisRepository userQrisRepository,
                      TransactionRepository transactionRepository,
                      PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userBankRepository = userBankRepository;
        this.userQrisRepository = userQrisRepository;
        this.transactionRepository = transactionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedPassword.isBlank()) {
            log.info("[SEED] SEED_PASSWORD not set — skipping user seeding");
            return;
        }

        if (userRepository.existsByUsername(seedUsername)) {
            log.info("[SEED] User '{}' already exists — skipping user creation", seedUsername);
        } else {
            User user = new User();
            user.setUsername(seedUsername);
            user.setPasswordHash(passwordEncoder.encode(seedPassword));
            user.setName(seedName);
            user.setBusinessName(seedBusinessName);
            user.setInitialBusinessName(seedBusinessName);
            String addr = seedBusinessAddress.isBlank() ? null : seedBusinessAddress;
            user.setBusinessAddress(addr);
            user.setInitialBusinessAddress(addr);
            String nmid = seedNmid.isBlank() ? null : seedNmid;
            user.setNmid(nmid);
            user.setInitialNmid(nmid);
            user.setEmail("admin@bejo.id");
            user.setPhone("+6281234567890");
            user.setAccountType(AccountType.MANAGED);
            userRepository.save(user);

            UserBank bank = new UserBank();
            bank.setUsername(seedUsername);
            bank.setBankName("Bank Central Asia");
            bank.setBankNumber("1234567890");
            bank.setBankAccountName("Admin Bejo");
            bank.setInitialBankName("Bank Central Asia");
            bank.setInitialBankNumber("1234567890");
            bank.setInitialBankAccountName("Admin Bejo");
            userBankRepository.save(bank);

            String seedQrisImage = loadSeedQrisImage();
            UserQris qris = new UserQris();
            qris.setUsername(seedUsername);
            qris.setInitialQrisImage(seedQrisImage);
            qris.setQrisImage(seedQrisImage);
            userQrisRepository.save(qris);

            log.info("[SEED] Created default user '{}' ({})", seedUsername, seedBusinessName);
        }

        seedTransactions(seedUsername);
    }

    private String loadSeedQrisImage() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("seed-qris.b64")) {
            if (is == null) {
                log.warn("[SEED] seed-qris.b64 not found in classpath — skipping QRIS image");
                return null;
            }
            return new String(is.readAllBytes());
        } catch (IOException e) {
            log.warn("[SEED] Failed to load seed-qris.b64: {}", e.getMessage());
            return null;
        }
    }

    private void seedTransactions(String username) {
        if (transactionRepository.existsByUsername(username)) return;

        LocalDateTime now = LocalDateTime.now();

        // Each entry: {daysAgo, hour, minute, amount, issuer, merchantPan, customerPan}
        // null customerPan = e-wallet (no PAN)
        Object[][] rows = {
            // Today — 5 transactions (two with 4s in amount)
            {0,  8, 44,   4000, "GoPay",        "936009XXXXXX0001", null},
            {0,  9, 14,  25000, "BNI",           "936009XXXXXX0001", "401177XXXXXX5678"},
            {0, 11, 32,  44000, "BCA",           "936009XXXXXX0001", "520153XXXXXX9012"},
            {0, 14,  5, 100000, "OVO",           "936009XXXXXX0001", null},
            {0, 17, 48,  15000, "Mandiri",       "936009XXXXXX0001", "489537XXXXXX3456"},
            // Yesterday — 4 transactions (one with 4 in amount)
            {1,  8, 20,  75000, "DANA",          "936009XXXXXX0001", null},
            {1, 10,  5,  14000, "BRI",           "936009XXXXXX0001", "672015XXXXXX7890"},
            {1, 13,  0,  35000, "ShopeePay",     "936009XXXXXX0001", null},
            {1, 19, 55, 200000, "GoPay",         "936009XXXXXX0001", "936009XXXXXX4321"},
            // 2 days ago — 4 transactions
            {2,  9, 30,  60000, "BCA",           "936009XXXXXX0001", null},
            {2, 12, 15,  80000, "BNI",           "936009XXXXXX0001", "401177XXXXXX1111"},
            {2, 15, 40,  40000, "DANA",          "936009XXXXXX0001", null},
            {2, 18, 20,  55000, "OVO",           "936009XXXXXX0001", "520153XXXXXX2222"},
            // 3 days ago — 2 transactions
            {3, 11, 10, 140000, "Mandiri",       "936009XXXXXX0001", "489537XXXXXX5555"},
            {3, 16, 50,  30000, "GoPay",         "936009XXXXXX0001", null},
            // 4 days ago — 3 transactions
            {4,  9,  5,  45000, "BRI",           "936009XXXXXX0001", "672015XXXXXX6666"},
            {4, 12, 30, 150000, "BCA",           "936009XXXXXX0001", null},
            {4, 18, 15,  20000, "ShopeePay",     "936009XXXXXX0001", "936009XXXXXX7777"},
            // 5 days ago — 3 transactions (one with 4 in amount)
            {5,  8,  0,  90000, "BNI",           "936009XXXXXX0001", "401177XXXXXX8888"},
            {5, 13, 25, 400000, "Mandiri",       "936009XXXXXX0001", "520153XXXXXX3333"},
            {5, 17, 40,  70000, "DANA",          "936009XXXXXX0001", null},
            // 7 days ago — 2 transactions
            {7, 11, 50,  50000, "GoPay",         "936009XXXXXX0001", null},
            {7, 16, 25,  85000, "BCA",           "936009XXXXXX0001", "489537XXXXXX9999"},
            // 10 days ago — 2 transactions
            {10,  9, 15, 175000, "BRI",          "936009XXXXXX0001", "672015XXXXXX2222"},
            {10, 14,  0,  24000, "OVO",          "936009XXXXXX0001", null},
            // 14 days ago — 2 transactions
            {14,  8, 45,  55000, "Permata",      "936009XXXXXX0001", "936009XXXXXX4444"},
            {14, 20, 10, 440000, "ShopeePay",    "936009XXXXXX0001", null},
            // 20 days ago — 1 transaction
            {20, 10, 30, 250000, "BCA",          "936009XXXXXX0001", "401177XXXXXX5555"},
            // 25 days ago — 2 transactions
            {25,  9, 20,  70000, "GoPay",        "936009XXXXXX0001", null},
            {25, 13, 50, 120000, "Mandiri",      "936009XXXXXX0001", "520153XXXXXX6666"},
        };

        List<Transaction> txns = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            int daysAgo = (int) r[0];
            int hour    = (int) r[1];
            int minute  = (int) r[2];
            int amount  = (int) r[3];
            String issuer      = (String) r[4];
            String merchantPan = (String) r[5];
            String customerPan = (String) r[6];

            Transaction t = new Transaction();
            t.setTransactionId("TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            t.setTransactionDate(now.minusDays(daysAgo).withHour(hour).withMinute(minute).withSecond(0).withNano(0));
            t.setAmount((long) amount);
            t.setSource(issuer);
            t.setUsername(username);
            t.setRrn(String.format("%012d", (long) (Math.random() * 1_000_000_000_000L)));
            t.setMerchantPan(merchantPan);
            t.setCustomerPan(customerPan);
            t.setType(TransactionType.MANAGED);
            t.setAcquirer(Acquirer.ASTRAPAY);
            t.setStatus(TransactionStatus.SUCCESS);
            t.setScrapeDate(now);
            txns.add(t);
        }

        transactionRepository.saveAll(txns);
        log.info("[SEED] Created {} dummy transactions for user '{}'", txns.size(), username);
    }
}

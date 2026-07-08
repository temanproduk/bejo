package com.bejo.service;

import com.bejo.dto.request.CreatePurchaseRequestDto;
import com.bejo.dto.request.PurchaseItemRequestDto;
import com.bejo.dto.response.PurchaseResponseDto;
import com.bejo.entity.Product;
import com.bejo.entity.Purchase;
import com.bejo.entity.PurchaseItem;
import com.bejo.repository.ProductRepository;
import com.bejo.repository.PurchaseItemRepository;
import com.bejo.repository.PurchaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurchaseServiceTest {

    @Mock private PurchaseRepository purchaseRepository;
    @Mock private PurchaseItemRepository purchaseItemRepository;
    @Mock private ProductRepository productRepository;

    private PurchaseService service;

    @BeforeEach
    void setUp() {
        service = new PurchaseService(purchaseRepository, purchaseItemRepository, productRepository);
        // Purchase gets an id on save (needed for line items). Lenient: the reject
        // test never reaches the persist step.
        lenient().when(purchaseRepository.save(any(Purchase.class))).thenAnswer(inv -> {
            Purchase p = inv.getArgument(0);
            if (p.getId() == null) {
                try {
                    var f = Purchase.class.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(p, 100L);
                } catch (ReflectiveOperationException ignored) { }
            }
            return p;
        });
        lenient().when(purchaseItemRepository.save(any(PurchaseItem.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Product product(long id, String sku, Long costPrice, int stock) {
        Product p = new Product();
        p.setUsername("alice");
        p.setSku(sku);
        p.setName("Product " + sku);
        p.setCostPrice(costPrice);
        p.setStock(stock);
        try {
            var f = Product.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
        } catch (ReflectiveOperationException ignored) { }
        return p;
    }

    @Test
    void createRaisesStockAndComputesTotal() {
        Product a = product(1L, "A", 5_000L, 10);
        Product b = product(2L, "B", 8_000L, 3);
        when(productRepository.findByIdAndUsername(1L, "alice")).thenReturn(Optional.of(a));
        when(productRepository.findByIdAndUsername(2L, "alice")).thenReturn(Optional.of(b));

        var request = new CreatePurchaseRequestDto("restock", List.of(
                new PurchaseItemRequestDto(1L, 20, null),      // 20 * 5000 (cost) = 100000
                new PurchaseItemRequestDto(2L, 5, 9_000L)));    // 5 * 9000 (override) = 45000

        PurchaseResponseDto data = service.create("alice", request);

        // Stock raised
        assertThat(a.getStock()).isEqualTo(30);
        assertThat(b.getStock()).isEqualTo(8);
        verify(productRepository, times(2)).save(any(Product.class));

        // Total = 100000 + 45000
        assertThat(data.totalAmount()).isEqualTo(145_000L);
        assertThat(data.items()).hasSize(2);
        assertThat(data.items().get(0).unitPrice()).isEqualTo(5_000L);   // fell back to cost
        assertThat(data.items().get(0).subtotal()).isEqualTo(100_000L);
        assertThat(data.items().get(1).unitPrice()).isEqualTo(9_000L);   // explicit override
    }

    @Test
    void unitPriceDefaultsToZeroWhenNoCostPrice() {
        Product a = product(1L, "A", null, 0);
        when(productRepository.findByIdAndUsername(1L, "alice")).thenReturn(Optional.of(a));

        var request = new CreatePurchaseRequestDto(null, List.of(new PurchaseItemRequestDto(1L, 4, null)));
        PurchaseResponseDto data = service.create("alice", request);

        assertThat(a.getStock()).isEqualTo(4);
        assertThat(data.totalAmount()).isZero();
        assertThat(data.items().getFirst().unitPrice()).isZero();
    }

    @Test
    void unknownProductRejectsWholePurchaseBeforeAnyStockChange() {
        Product a = product(1L, "A", 5_000L, 10);
        when(productRepository.findByIdAndUsername(1L, "alice")).thenReturn(Optional.of(a));
        when(productRepository.findByIdAndUsername(99L, "alice")).thenReturn(Optional.empty());

        var request = new CreatePurchaseRequestDto(null, List.of(
                new PurchaseItemRequestDto(1L, 5, null),
                new PurchaseItemRequestDto(99L, 2, null)));

        assertThatThrownBy(() -> service.create("alice", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Produk tidak ditemukan");

        // No stock change and nothing persisted (validation happens in pass 1)
        assertThat(a.getStock()).isEqualTo(10);
        verify(productRepository, never()).save(any());
        verify(purchaseRepository, never()).save(any());
        verify(purchaseItemRepository, never()).save(any());
    }
}

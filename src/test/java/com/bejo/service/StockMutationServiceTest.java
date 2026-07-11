package com.bejo.service;

import com.bejo.dto.request.CreatePurchaseRequestDto;
import com.bejo.dto.request.PurchaseItemRequestDto;
import com.bejo.dto.response.StockMutationResponseDto;
import com.bejo.entity.Product;
import com.bejo.entity.StockMutation;
import com.bejo.entity.StockMutationItem;
import com.bejo.repository.ProductRepository;
import com.bejo.repository.StockMutationItemRepository;
import com.bejo.repository.StockMutationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class StockMutationServiceTest {

    private static final Long BIZ = 1L;

    @Mock private StockMutationRepository stockMutationRepository;
    @Mock private StockMutationItemRepository stockMutationItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private BusinessContext businessContext;

    private StockMutationService service;

    @BeforeEach
    void setUp() {
        service = new StockMutationService(stockMutationRepository, stockMutationItemRepository,
                productRepository, businessContext);
        lenient().when(businessContext.requireBusinessId("alice")).thenReturn(BIZ);
        // StockMutation gets an id on save (needed for line items). Lenient: the reject
        // test never reaches the persist step.
        lenient().when(stockMutationRepository.save(any(StockMutation.class))).thenAnswer(inv -> {
            StockMutation p = inv.getArgument(0);
            if (p.getId() == null) {
                try {
                    var f = StockMutation.class.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(p, 100L);
                } catch (ReflectiveOperationException ignored) { }
            }
            return p;
        });
        lenient().when(stockMutationItemRepository.save(any(StockMutationItem.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Product product(long id, String sku, Long costPrice, int stock) {
        Product p = new Product();
        p.setBusinessId(BIZ);
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
        when(productRepository.findByIdAndBusinessId(1L, BIZ)).thenReturn(Optional.of(a));
        when(productRepository.findByIdAndBusinessId(2L, BIZ)).thenReturn(Optional.of(b));

        var request = new CreatePurchaseRequestDto("restock", List.of(
                new PurchaseItemRequestDto(1L, 20, null),      // 20 * 5000 (cost) = 100000
                new PurchaseItemRequestDto(2L, 5, 9_000L)));    // 5 * 9000 (override) = 45000

        StockMutationResponseDto data = service.create("alice", request);

        // Stock raised
        assertThat(a.getStock()).isEqualTo(30);
        assertThat(b.getStock()).isEqualTo(8);
        verify(productRepository, times(2)).save(any(Product.class));

        // Total = 100000 + 45000
        assertThat(data.type()).isEqualTo("PURCHASE");
        assertThat(data.totalAmount()).isEqualTo(145_000L);
        assertThat(data.items()).hasSize(2);
        assertThat(data.items().get(0).unitPrice()).isEqualTo(5_000L);   // fell back to cost
        assertThat(data.items().get(0).subtotal()).isEqualTo(100_000L);
        assertThat(data.items().get(0).previousStock()).isEqualTo(10);
        assertThat(data.items().get(0).newStock()).isEqualTo(30);
        assertThat(data.items().get(0).quantityChange()).isEqualTo(20);
        assertThat(data.items().get(1).unitPrice()).isEqualTo(9_000L);   // explicit override
    }

    @Test
    void recordStockChangePersistsAdjustmentMutationWithoutPrices() {
        Product a = product(1L, "A", null, 8);

        service.recordStockChange(BIZ, "ADJUSTMENT", "Stock opname",
                List.of(new StockMutationService.StockChangeLine(a, 5, 8)));

        ArgumentCaptor<StockMutation> mc = ArgumentCaptor.forClass(StockMutation.class);
        verify(stockMutationRepository).save(mc.capture());
        assertThat(mc.getValue().getType()).isEqualTo("ADJUSTMENT");

        ArgumentCaptor<StockMutationItem> ic = ArgumentCaptor.forClass(StockMutationItem.class);
        verify(stockMutationItemRepository).save(ic.capture());
        assertThat(ic.getValue().getPreviousStock()).isEqualTo(5);
        assertThat(ic.getValue().getNewStock()).isEqualTo(8);
        assertThat(ic.getValue().getQuantityChange()).isEqualTo(3);
        assertThat(ic.getValue().getUnitPrice()).isNull();   // non-purchase mutations aren't priced
    }

    @Test
    void unitPriceDefaultsToZeroWhenNoCostPrice() {
        Product a = product(1L, "A", null, 0);
        when(productRepository.findByIdAndBusinessId(1L, BIZ)).thenReturn(Optional.of(a));

        var request = new CreatePurchaseRequestDto(null, List.of(new PurchaseItemRequestDto(1L, 4, null)));
        StockMutationResponseDto data = service.create("alice", request);

        assertThat(a.getStock()).isEqualTo(4);
        assertThat(data.totalAmount()).isZero();
        assertThat(data.items().getFirst().unitPrice()).isZero();
    }

    @Test
    void unknownProductRejectsWholePurchaseBeforeAnyStockChange() {
        Product a = product(1L, "A", 5_000L, 10);
        when(productRepository.findByIdAndBusinessId(1L, BIZ)).thenReturn(Optional.of(a));
        when(productRepository.findByIdAndBusinessId(99L, BIZ)).thenReturn(Optional.empty());

        var request = new CreatePurchaseRequestDto(null, List.of(
                new PurchaseItemRequestDto(1L, 5, null),
                new PurchaseItemRequestDto(99L, 2, null)));

        assertThatThrownBy(() -> service.create("alice", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Produk tidak ditemukan");

        // No stock change and nothing persisted (validation happens in pass 1)
        assertThat(a.getStock()).isEqualTo(10);
        verify(productRepository, never()).save(any());
        verify(stockMutationRepository, never()).save(any());
        verify(stockMutationItemRepository, never()).save(any());
    }
}

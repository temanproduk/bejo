package com.bejo.service;

import com.bejo.dto.request.CreateProductRequestDto;
import com.bejo.dto.request.UpdateProductRequestDto;
import com.bejo.dto.response.ProductResponseDto;
import com.bejo.entity.Product;
import com.bejo.entity.ProductCategory;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.ProductCategoryRepository;
import com.bejo.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductCategoryRepository categoryRepository;
    @Mock private StockMutationService stockMutationService;

    private ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(productRepository, categoryRepository, stockMutationService);
    }

    private CreateProductRequestDto createReq(String sku, Long categoryId) {
        return new CreateProductRequestDto(sku, "Kopi Susu", "Kopi + susu segar", categoryId, 18_000L, 10_000L, 25, null);
    }

    private ProductCategory category(Long id, String name) {
        ProductCategory c = new ProductCategory();
        c.setUsername("alice");
        c.setName(name);
        return c;
    }

    @Test
    void createSavesWithDefaultsAndCategory() {
        when(productRepository.existsByUsernameAndSku("alice", "SKU1")).thenReturn(false);
        when(categoryRepository.findByIdAndUsername(3L, "alice")).thenReturn(Optional.of(category(3L, "Minuman")));

        ProductResponseDto data = service.create("alice", createReq("SKU1", 3L));

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        Product saved = captor.getValue();
        assertThat(saved.getSku()).isEqualTo("SKU1");
        assertThat(saved.getPrice()).isEqualTo(18_000L);
        assertThat(saved.getStock()).isEqualTo(25);
        assertThat(saved.getCategoryId()).isEqualTo(3L);
        assertThat(saved.getShortDescription()).isEqualTo("Kopi + susu segar");
        assertThat(data.categoryName()).isEqualTo("Minuman");
        assertThat(data.shortDescription()).isEqualTo("Kopi + susu segar");
    }

    @Test
    void createRejectsDuplicateSku() {
        when(productRepository.existsByUsernameAndSku("alice", "SKU1")).thenReturn(true);

        assertThatThrownBy(() -> service.create("alice", createReq("SKU1", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKU sudah digunakan");
        verify(productRepository, never()).save(any());
    }

    @Test
    void createRejectsCategoryFromAnotherMerchant() {
        when(productRepository.existsByUsernameAndSku("alice", "SKU1")).thenReturn(false);
        when(categoryRepository.findByIdAndUsername(99L, "alice")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("alice", createReq("SKU1", 99L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Kategori tidak ditemukan");
        verify(productRepository, never()).save(any());
    }

    @Test
    void createRejectsOversizedPhoto() {
        when(productRepository.existsByUsernameAndSku("alice", "SKU1")).thenReturn(false);
        String bigPhoto = Base64.getEncoder().encodeToString(new byte[512_001]);
        var req = new CreateProductRequestDto("SKU1", "Kopi", null, null, 1000L, null, 0, bigPhoto);

        assertThatThrownBy(() -> service.create("alice", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500KB");
        verify(productRepository, never()).save(any());
    }

    @Test
    void createRejectsInvalidBase64Photo() {
        when(productRepository.existsByUsernameAndSku("alice", "SKU1")).thenReturn(false);
        var req = new CreateProductRequestDto("SKU1", "Kopi", null, null, 1000L, null, 0, "@@ not base64 @@");

        assertThatThrownBy(() -> service.create("alice", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64");
        verify(productRepository, never()).save(any());
    }

    @Test
    void getUnknownProductIs404() {
        when(productRepository.findByIdAndUsername(7L, "alice")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("alice", 7L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateAppliesOnlyProvidedFields() {
        Product p = new Product();
        p.setUsername("alice");
        p.setSku("SKU1");
        p.setName("Kopi");
        p.setPrice(10_000L);
        p.setStock(5);
        when(productRepository.findByIdAndUsername(1L, "alice")).thenReturn(Optional.of(p));

        // Only stock + price changed (inventory update)
        var req = new UpdateProductRequestDto(null, null, "Diskon hari ini", null, 12_000L, null, 8, null);
        ProductResponseDto data = service.update("alice", 1L, req);

        assertThat(data.price()).isEqualTo(12_000L);
        assertThat(data.stock()).isEqualTo(8);
        assertThat(data.shortDescription()).isEqualTo("Diskon hari ini"); // editable
        assertThat(data.name()).isEqualTo("Kopi"); // unchanged
        verify(productRepository).save(p);
    }

    @Test
    void updateWithNoFieldsIsRejected() {
        var empty = new UpdateProductRequestDto(null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update("alice", 1L, empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tidak ada field");
        verify(productRepository, never()).save(any());
    }

    @Test
    void updateRejectsSkuCollisionWithAnotherProduct() {
        Product p = new Product();
        p.setUsername("alice");
        p.setSku("SKU1");
        when(productRepository.findByIdAndUsername(1L, "alice")).thenReturn(Optional.of(p));
        when(productRepository.existsByUsernameAndSku("alice", "SKU2")).thenReturn(true);

        var req = new UpdateProductRequestDto("SKU2", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update("alice", 1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKU sudah digunakan");
        verify(productRepository, never()).save(any());
    }

    @Test
    void deleteUnknownProductIs404() {
        when(productRepository.findByIdAndUsername(7L, "alice")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("alice", 7L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(productRepository, never()).delete(any());
    }

    private Product stocked(long id, String sku, int stock) {
        Product p = new Product();
        p.setUsername("alice");
        p.setSku(sku);
        p.setName("Product " + sku);
        p.setStock(stock);
        try {
            var f = Product.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
        } catch (ReflectiveOperationException ignored) { }
        return p;
    }

    @Test
    void adjustStockSetsCountsAndReportsVariance() {
        Product a = stocked(1L, "A", 10);
        Product b = stocked(2L, "B", 5);
        when(productRepository.findByIdAndUsername(1L, "alice")).thenReturn(Optional.of(a));
        when(productRepository.findByIdAndUsername(2L, "alice")).thenReturn(Optional.of(b));

        var request = new com.bejo.dto.request.StockAdjustmentRequestDto(List.of(
                new com.bejo.dto.request.StockAdjustmentItemDto(1L, 8),    // counted 8 (−2)
                new com.bejo.dto.request.StockAdjustmentItemDto(2L, 12))); // counted 12 (+7)

        var result = service.adjustStock("alice", request);

        assertThat(a.getStock()).isEqualTo(8);
        assertThat(b.getStock()).isEqualTo(12);
        verify(productRepository, times(2)).save(any(Product.class));

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items().get(0).previousStock()).isEqualTo(10);
        assertThat(result.items().get(0).newStock()).isEqualTo(8);
        assertThat(result.items().get(0).difference()).isEqualTo(-2);
        assertThat(result.items().get(1).difference()).isEqualTo(7);
    }

    @Test
    void adjustStockUnknownProductRejectsWholeBatch() {
        Product a = stocked(1L, "A", 10);
        when(productRepository.findByIdAndUsername(1L, "alice")).thenReturn(Optional.of(a));
        when(productRepository.findByIdAndUsername(99L, "alice")).thenReturn(Optional.empty());

        var request = new com.bejo.dto.request.StockAdjustmentRequestDto(List.of(
                new com.bejo.dto.request.StockAdjustmentItemDto(1L, 8),
                new com.bejo.dto.request.StockAdjustmentItemDto(99L, 3)));

        assertThatThrownBy(() -> service.adjustStock("alice", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Produk tidak ditemukan");

        // Atomic: nothing changed or saved (validation is pass 1)
        assertThat(a.getStock()).isEqualTo(10);
        verify(productRepository, never()).save(any());
    }
}

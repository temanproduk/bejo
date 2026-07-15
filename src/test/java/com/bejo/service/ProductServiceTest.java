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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final Long BIZ = 1L;

    @Mock private ProductRepository productRepository;
    @Mock private ProductCategoryRepository categoryRepository;
    @Mock private StockMutationService stockMutationService;
    @Mock private BusinessContext businessContext;

    private ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(productRepository, categoryRepository, stockMutationService, businessContext);
        lenient().when(businessContext.requireBusinessId("alice")).thenReturn(BIZ);
    }

    private CreateProductRequestDto createReq(String sku, Long categoryId) {
        return new CreateProductRequestDto(sku, "Kopi Susu", "Kopi + susu segar", categoryId, 18_000L, 10_000L, 25, null);
    }

    private ProductCategory category(Long id, String name) {
        ProductCategory c = new ProductCategory();
        c.setBusinessId(BIZ);
        c.setName(name);
        return c;
    }

    @Test
    void createSavesWithDefaultsAndCategory() {
        when(productRepository.existsByBusinessIdAndSku(BIZ, "SKU1")).thenReturn(false);
        when(categoryRepository.findByIdAndBusinessId(3L, BIZ)).thenReturn(Optional.of(category(3L, "Minuman")));

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
        when(productRepository.existsByBusinessIdAndSku(BIZ, "SKU1")).thenReturn(true);

        assertThatThrownBy(() -> service.create("alice", createReq("SKU1", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKU sudah digunakan");
        verify(productRepository, never()).save(any());
    }

    @Test
    void createRejectsCategoryFromAnotherBusiness() {
        when(productRepository.existsByBusinessIdAndSku(BIZ, "SKU1")).thenReturn(false);
        when(categoryRepository.findByIdAndBusinessId(99L, BIZ)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("alice", createReq("SKU1", 99L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Kategori tidak ditemukan");
        verify(productRepository, never()).save(any());
    }

    @Test
    void createRejectsOversizedPhoto() {
        when(productRepository.existsByBusinessIdAndSku(BIZ, "SKU1")).thenReturn(false);
        String bigPhoto = Base64.getEncoder().encodeToString(new byte[512_001]);
        var req = new CreateProductRequestDto("SKU1", "Kopi", null, null, 1000L, null, 0, bigPhoto);

        assertThatThrownBy(() -> service.create("alice", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500KB");
        verify(productRepository, never()).save(any());
    }

    @Test
    void createRejectsInvalidBase64Photo() {
        when(productRepository.existsByBusinessIdAndSku(BIZ, "SKU1")).thenReturn(false);
        var req = new CreateProductRequestDto("SKU1", "Kopi", null, null, 1000L, null, 0, "@@ not base64 @@");

        assertThatThrownBy(() -> service.create("alice", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tidak valid");
        verify(productRepository, never()).save(any());
    }

    @Test
    void getUnknownProductIs404() {
        when(productRepository.findByIdAndBusinessId(7L, BIZ)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("alice", 7L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateAppliesOnlyProvidedFields() {
        Product p = new Product();
        p.setBusinessId(BIZ);
        p.setSku("SKU1");
        p.setName("Kopi");
        p.setPrice(10_000L);
        p.setStock(5);
        when(productRepository.findByIdAndBusinessId(1L, BIZ)).thenReturn(Optional.of(p));

        // Only stock + price changed (inventory update)
        var req = new UpdateProductRequestDto(null, null, "Diskon hari ini", null, 12_000L, null, 8, null);
        ProductResponseDto data = service.update("alice", 1L, req);

        assertThat(data.price()).isEqualTo(12_000L);
        assertThat(data.stock()).isEqualTo(8);
        assertThat(data.shortDescription()).isEqualTo("Diskon hari ini"); // editable
        assertThat(data.name()).isEqualTo("Kopi"); // unchanged
        verify(productRepository).save(p);
        // Stock changed 5 -> 8, so an UPDATE mutation is recorded
        verify(stockMutationService).recordStockChange(eq(BIZ), eq("UPDATE"), any(), any());
    }

    @Test
    void updateWithNoFieldsIsRejected() {
        var empty = new UpdateProductRequestDto(null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update("alice", 1L, empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tidak ada data");
        verify(productRepository, never()).save(any());
    }

    @Test
    void updateRejectsSkuCollisionWithAnotherProduct() {
        Product p = new Product();
        p.setBusinessId(BIZ);
        p.setSku("SKU1");
        when(productRepository.findByIdAndBusinessId(1L, BIZ)).thenReturn(Optional.of(p));
        when(productRepository.existsByBusinessIdAndSku(BIZ, "SKU2")).thenReturn(true);

        var req = new UpdateProductRequestDto("SKU2", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update("alice", 1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKU sudah digunakan");
        verify(productRepository, never()).save(any());
    }

    @Test
    void deleteUnknownProductIs404() {
        when(productRepository.findByIdAndBusinessId(7L, BIZ)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("alice", 7L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(productRepository, never()).delete(any());
    }

    private Product stocked(long id, String sku, int stock) {
        Product p = new Product();
        p.setBusinessId(BIZ);
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
        when(productRepository.findByIdAndBusinessId(1L, BIZ)).thenReturn(Optional.of(a));
        when(productRepository.findByIdAndBusinessId(2L, BIZ)).thenReturn(Optional.of(b));

        var request = new com.bejo.dto.request.StockAdjustmentRequestDto("opname sore", List.of(
                new com.bejo.dto.request.StockAdjustmentItemDto(1L, 8),    // counted 8 (−2)
                new com.bejo.dto.request.StockAdjustmentItemDto(2L, 12))); // counted 12 (+7)

        var result = service.adjustStock("alice", request);

        assertThat(a.getStock()).isEqualTo(8);
        assertThat(b.getStock()).isEqualTo(12);
        verify(productRepository, times(2)).save(any(Product.class));
        // The note flows onto the recorded ADJUSTMENT mutation
        verify(stockMutationService).recordStockChange(eq(BIZ), eq("ADJUSTMENT"), eq("opname sore"), any());

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items().get(0).previousStock()).isEqualTo(10);
        assertThat(result.items().get(0).newStock()).isEqualTo(8);
        assertThat(result.items().get(0).difference()).isEqualTo(-2);
        assertThat(result.items().get(1).difference()).isEqualTo(7);
    }

    @Test
    void adjustStockUnknownProductRejectsWholeBatch() {
        Product a = stocked(1L, "A", 10);
        when(productRepository.findByIdAndBusinessId(1L, BIZ)).thenReturn(Optional.of(a));
        when(productRepository.findByIdAndBusinessId(99L, BIZ)).thenReturn(Optional.empty());

        var request = new com.bejo.dto.request.StockAdjustmentRequestDto(null, List.of(
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

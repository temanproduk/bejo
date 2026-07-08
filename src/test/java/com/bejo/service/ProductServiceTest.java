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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductCategoryRepository categoryRepository;

    private ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(productRepository, categoryRepository);
    }

    private CreateProductRequestDto createReq(String sku, Long categoryId) {
        return new CreateProductRequestDto(sku, "Kopi Susu", categoryId, 18_000L, 10_000L, 25, null);
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
        assertThat(data.categoryName()).isEqualTo("Minuman");
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
        var req = new CreateProductRequestDto("SKU1", "Kopi", null, 1000L, null, 0, bigPhoto);

        assertThatThrownBy(() -> service.create("alice", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500KB");
        verify(productRepository, never()).save(any());
    }

    @Test
    void createRejectsInvalidBase64Photo() {
        when(productRepository.existsByUsernameAndSku("alice", "SKU1")).thenReturn(false);
        var req = new CreateProductRequestDto("SKU1", "Kopi", null, 1000L, null, 0, "@@ not base64 @@");

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
        var req = new UpdateProductRequestDto(null, null, null, 12_000L, null, 8, null);
        ProductResponseDto data = service.update("alice", 1L, req);

        assertThat(data.price()).isEqualTo(12_000L);
        assertThat(data.stock()).isEqualTo(8);
        assertThat(data.name()).isEqualTo("Kopi"); // unchanged
        verify(productRepository).save(p);
    }

    @Test
    void updateWithNoFieldsIsRejected() {
        var empty = new UpdateProductRequestDto(null, null, null, null, null, null, null);

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

        var req = new UpdateProductRequestDto("SKU2", null, null, null, null, null, null);

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
}

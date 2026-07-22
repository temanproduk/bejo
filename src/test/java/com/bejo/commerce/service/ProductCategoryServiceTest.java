package com.bejo.commerce.service;
import com.bejo.core.service.BusinessContext;

import com.bejo.commerce.dto.CreateCategoryRequestDto;
import com.bejo.commerce.dto.CategoryResponseDto;
import com.bejo.commerce.domain.ProductCategory;
import com.bejo.shared.exception.ResourceNotFoundException;
import com.bejo.commerce.repository.ProductCategoryRepository;
import com.bejo.commerce.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductCategoryServiceTest {

    private static final Long BIZ = 1L;

    @Mock private ProductCategoryRepository categoryRepository;
    @Mock private ProductRepository productRepository;
    @Mock private BusinessContext businessContext;

    private ProductCategoryService service;

    @BeforeEach
    void setUp() {
        service = new ProductCategoryService(categoryRepository, productRepository, businessContext);
        lenient().when(businessContext.requireBusinessId("alice")).thenReturn(BIZ);
    }

    @Test
    void createTrimsAndSaves() {
        when(categoryRepository.existsByBusinessIdAndName(BIZ, "Minuman")).thenReturn(false);

        CategoryResponseDto data = service.create("alice", new CreateCategoryRequestDto("  Minuman  "));

        ArgumentCaptor<ProductCategory> captor = ArgumentCaptor.forClass(ProductCategory.class);
        verify(categoryRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Minuman");
        assertThat(captor.getValue().getBusinessId()).isEqualTo(BIZ);
        assertThat(data.name()).isEqualTo("Minuman");
    }

    @Test
    void createRejectsDuplicateName() {
        when(categoryRepository.existsByBusinessIdAndName(BIZ, "Minuman")).thenReturn(true);

        assertThatThrownBy(() -> service.create("alice", new CreateCategoryRequestDto("Minuman")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sudah ada");
        verify(categoryRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deleteUncategorizesProductsThenRemovesCategory() {
        ProductCategory category = new ProductCategory();
        category.setBusinessId(BIZ);
        category.setName("Minuman");
        when(categoryRepository.findByIdAndBusinessId(5L, BIZ)).thenReturn(Optional.of(category));

        service.delete("alice", 5L);

        verify(productRepository).clearCategory(BIZ, 5L);
        verify(categoryRepository).delete(category);
    }

    @Test
    void deleteUnknownCategoryIs404() {
        when(categoryRepository.findByIdAndBusinessId(9L, BIZ)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("alice", 9L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(categoryRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }
}

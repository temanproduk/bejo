package com.bejo.service;

import com.bejo.dto.request.CreateCategoryRequestDto;
import com.bejo.dto.response.CategoryResponseDto;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductCategoryServiceTest {

    @Mock private ProductCategoryRepository categoryRepository;
    @Mock private ProductRepository productRepository;

    private ProductCategoryService service;

    @BeforeEach
    void setUp() {
        service = new ProductCategoryService(categoryRepository, productRepository);
    }

    @Test
    void createTrimsAndSaves() {
        when(categoryRepository.existsByUsernameAndName("alice", "Minuman")).thenReturn(false);

        CategoryResponseDto data = service.create("alice", new CreateCategoryRequestDto("  Minuman  "));

        ArgumentCaptor<ProductCategory> captor = ArgumentCaptor.forClass(ProductCategory.class);
        verify(categoryRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Minuman");
        assertThat(captor.getValue().getUsername()).isEqualTo("alice");
        assertThat(data.name()).isEqualTo("Minuman");
    }

    @Test
    void createRejectsDuplicateName() {
        when(categoryRepository.existsByUsernameAndName("alice", "Minuman")).thenReturn(true);

        assertThatThrownBy(() -> service.create("alice", new CreateCategoryRequestDto("Minuman")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sudah ada");
        verify(categoryRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deleteUncategorizesProductsThenRemovesCategory() {
        ProductCategory category = new ProductCategory();
        category.setUsername("alice");
        category.setName("Minuman");
        when(categoryRepository.findByIdAndUsername(5L, "alice")).thenReturn(Optional.of(category));

        service.delete("alice", 5L);

        verify(productRepository).clearCategory("alice", 5L);
        verify(categoryRepository).delete(category);
    }

    @Test
    void deleteUnknownCategoryIs404() {
        when(categoryRepository.findByIdAndUsername(9L, "alice")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("alice", 9L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(categoryRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }
}

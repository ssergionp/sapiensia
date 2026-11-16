package br.com.sergio.sapiensia;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestJvmHeapTest {

    private static final long ONE_GIB = 1024L * 1024 * 1024;

    @Test
    void shouldRunTheTestsWithABoundedHeap() {
        assertThat(Runtime.getRuntime().maxMemory()).isLessThanOrEqualTo(ONE_GIB);
    }
}

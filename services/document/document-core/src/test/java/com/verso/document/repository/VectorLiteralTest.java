package com.verso.document.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** pgvector's text input: exact floats, and never NaN or infinity (the column would reject them anyway, later). */
class VectorLiteralTest {

    @Test
    void vectorLiteral_whenFinite_writesEveryValueExactly() {
        assertThat(IngestionRepository.vectorLiteral(new float[]{0.5f, -1.25f, 1.0E-7f})).isEqualTo("[0.5,-1.25,1.0E-7]");
    }

    @Test
    void vectorLiteral_whenAValueIsNotFinite_refuses() {
        assertThatThrownBy(() -> IngestionRepository.vectorLiteral(new float[]{0f, Float.NaN}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

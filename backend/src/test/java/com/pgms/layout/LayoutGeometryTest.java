package com.pgms.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.common.exception.ApiException;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.layout.LayoutGeometry.Rect;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutGeometryTest {

    private static Rect rect(String name, int x, int y, int w, int h) {
        return new Rect(null, name, x, y, w, h);
    }

    @Test
    void allNullGeometryMeansUnplaced() {
        assertThat(LayoutGeometry.toRect(1L, "space 101", null, null, null, null)).isEmpty();
        assertThat(LayoutGeometry.toRect(1L, "space 101", 0, 0, 1, 1)).contains(new Rect(1L, "space 101", 0, 0, 1, 1));
    }

    @Test
    void partialOrNegativeGeometryIsAMalformedRequest() {
        assertThatThrownBy(() -> LayoutGeometry.toRect(1L, "s", 1, 1, null, 2))
                .isInstanceOf(BadRequestException.class).extracting("code").isEqualTo("INVALID_GEOMETRY");
        assertThatThrownBy(() -> LayoutGeometry.toRect(1L, "s", -1, 0, 2, 2)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> LayoutGeometry.toRect(1L, "s", 0, 0, 0, 2)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> LayoutGeometry.toRect(1L, "s", 0, 0, 2, 0)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void spacesSharingAnEdgeDoNotOverlap() {
        Rect left = rect("a", 0, 0, 4, 3);
        assertThat(left.overlaps(rect("right", 4, 0, 2, 3))).isFalse();
        assertThat(left.overlaps(rect("below", 0, 3, 4, 1))).isFalse();
        assertThat(left.overlaps(rect("corner", 4, 3, 1, 1))).isFalse();
        assertThatCode(() -> LayoutGeometry.requireNoOverlap(List.of(left, rect("b", 4, 0, 2, 3), rect("c", 0, 3, 6, 2))))
                .doesNotThrowAnyException();
    }

    @Test
    void overlappingSpacesAreRejectedNamingThePair() {
        assertThat(rect("a", 0, 0, 4, 4).overlaps(rect("inside", 1, 1, 1, 1))).isTrue();
        assertThat(rect("a", 2, 2, 2, 2).overlaps(rect("around", 0, 0, 10, 10))).isTrue();
        assertThatThrownBy(() -> LayoutGeometry.requireNoOverlap(List.of(
                rect("space 101", 0, 0, 4, 3), rect("space 102", 10, 0, 2, 2), rect("space 103", 3, 2, 2, 2))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("space 101").hasMessageContaining("space 103")
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("LAYOUT_OVERLAP");
    }

    @Test
    void overlapIsFoundEvenWhenSortedNeighboursAreFarApart() {
        // a tall space at x=0 and a wide one starting at x=5 that reaches back over it on another row
        assertThatThrownBy(() -> LayoutGeometry.requireNoOverlap(List.of(
                rect("tall", 0, 0, 1, 10), rect("mid", 2, 0, 1, 1), rect("wide", 0, 5, 8, 1))))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void spacesMustStayInsideTheGrid() {
        assertThatCode(() -> LayoutGeometry.requireInsideGrid(24, 16, List.of(rect("full", 0, 0, 24, 16))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> LayoutGeometry.validate(24, 16, List.of(rect("space 9", 20, 0, 5, 2))))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("LAYOUT_OUT_OF_BOUNDS");
        assertThatThrownBy(() -> LayoutGeometry.requireInsideGrid(24, 16, List.of(rect("low", 0, 15, 1, 2))))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void namesSpacesForMessages() {
        assertThat(LayoutGeometry.nameOf("101")).isEqualTo("space 101");
    }
}

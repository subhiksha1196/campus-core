package com.sis.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class Base62Test {
    @Test
    void encodesKnownValues() {
        assertEquals("0", Base62.encode(0));
        assertEquals("z", Base62.encode(35));
        assertEquals("10", Base62.encode(62));
    }

    @Test
    void offsetGivesAtLeastSevenCharacters() {
        assertEquals(7, Base62.encode(56_800_235_584L + 1).length());
    }

    @Test
    void rejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> Base62.encode(-1));
    }
}

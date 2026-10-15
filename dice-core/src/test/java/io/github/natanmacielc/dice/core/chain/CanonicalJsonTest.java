package io.github.natanmacielc.dice.core.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

    @Test
    void sortsKeysAndOmitsWhitespace() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", List.of(2, 1));
        m.put("a", Map.of("z", 1, "y", "x"));
        m.put("A", 0);
        assertEquals("{\"A\":0,\"a\":{\"y\":\"x\",\"z\":1},\"b\":[2,1]}", CanonicalJson.write(m));
    }

    @Test
    void escapesOnlyWhatJcsEscapes() {
        assertEquals("\"\\\"\\\\\\b\\t\\n\\f\\r\\u0000\\u001f/\u007fé😀\"",
                CanonicalJson.write("\"\\\b\t\n\f\r\u0000\u001f/\u007fé😀"));
    }

    @Test
    void integersInSafeRange() {
        assertEquals("[-9007199254740991,0,9007199254740991]",
                CanonicalJson.write(List.of(-9007199254740991L, 0, 9007199254740991L)));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(9007199254740992L));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(-9007199254740992L));
    }

    @Test
    void rejectsFloatBooleanNullAndNonStringKeys() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(1.5));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(1.0));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(true));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Arrays.asList(1, null)));
        Map<Object, Object> weird = new HashMap<>();
        weird.put(1, 1);
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(weird));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write("a\uD800"));
    }
}

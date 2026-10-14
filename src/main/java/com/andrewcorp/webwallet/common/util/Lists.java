package com.andrewcorp.webwallet.common.util;

import java.util.List;

public final class Lists {

    private Lists() {}

    /** External APIs and files omit empty lists. */
    public static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}

package com.mandi.shared;

/** Builds LIKE patterns that treat user input literally (escapes %, _ and the escape character). */
public final class Like {
    /** The backslash character, written as a code point to keep this file free of escape sequences. */
    public static final char ESCAPE = (char) 0x5C;

    private static final String ESC = String.valueOf(ESCAPE);

    private Like() {
    }

    public static String contains(String input) {
        String escaped = input.trim().toLowerCase()
                .replace(ESC, ESC + ESC)
                .replace("%", ESC + "%")
                .replace("_", ESC + "_");
        return "%" + escaped + "%";
    }
}

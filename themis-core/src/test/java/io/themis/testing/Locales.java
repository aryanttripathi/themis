package io.themis.testing;

import java.util.Locale;
import java.util.function.Supplier;

/** Runs code under a different default locale (e.g. tr-TR, where "i".toUpperCase() is "İ"). */
public final class Locales {

    public static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private Locales() {
    }

    public static <T> T with(Locale locale, Supplier<T> body) {
        Locale previous = Locale.getDefault();
        Locale.setDefault(locale);
        try {
            return body.get();
        } finally {
            Locale.setDefault(previous);
        }
    }
}

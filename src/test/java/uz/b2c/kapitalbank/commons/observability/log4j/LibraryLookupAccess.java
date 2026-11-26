package uz.b2c.kapitalbank.commons.observability.log4j;

/** Доступ к lookup из тестов другого пакета. */
public final class LibraryLookupAccess {

    private LibraryLookupAccess() {
    }

    public static String lookup(final String key) {
        return new LibraryLookup().lookup(key);
    }
}

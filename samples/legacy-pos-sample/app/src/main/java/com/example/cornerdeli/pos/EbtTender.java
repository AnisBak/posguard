// FICTIONAL, DELIBERATELY INSECURE SAMPLE. See the README at the sample root. Never copy.
package com.example.cornerdeli.pos;

/** EBT checkout written before chip EBT cards: swipe first, chip only as an afterthought. */
public class EbtTender {
    private final CardReader reader;

    public EbtTender(CardReader reader) {
        this.reader = reader;
    }

    public boolean readEbtCard() {
        if (reader.swipeCard()) {
            return true;
        }
        return reader.insertChip();
    }

    /** Fictional reader interface. */
    public interface CardReader {
        boolean swipeCard();
        boolean insertChip();
    }
}

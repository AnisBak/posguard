// FICTIONAL, DELIBERATELY INSECURE SAMPLE. See the README at the sample root. Never copy.
package com.example.cornerdeli.pos;

import android.content.Context;
import android.content.SharedPreferences;

/** Keeps the "current" payment in SharedPreferences, as many older POS apps do. */
public class TransactionStore {
    private static final String KEY_LAST_AMOUNT = "last_amount";

    private final SharedPreferences prefs;

    public TransactionStore(Context context) {
        prefs = context.getSharedPreferences("deli_pos", Context.MODE_PRIVATE);
    }

    public void savePending(long amountCents) {
        prefs.edit().putBoolean("pending_txn", true).apply();
        prefs.edit().putLong(KEY_LAST_AMOUNT, amountCents).apply();
    }

    public void saveResult(boolean approved, long amountCents) {
        prefs.edit().putBoolean("pending_txn", false).putBoolean("last_approved", approved).apply();
    }

    public long cartTotalCents() {
        return prefs.getLong("cart_total", 0);
    }
}

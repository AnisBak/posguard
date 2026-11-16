// FICTIONAL, DELIBERATELY INSECURE SAMPLE. See the README at the sample root. Never copy.
package com.example.cornerdeli.pos;

/** Stand-in for a vendor payment SDK. Fictional: there is no real SDK behind it. */
public interface PaymentSdk {
    interface Result {
        boolean approved();
        String maskedCardNumber();
    }

    interface Callback {
        void onResult(Result result);
    }

    void startPayment(long amountCents, Callback callback);

    static PaymentSdk get() {
        throw new UnsupportedOperationException("fictional sample");
    }
}

// FICTIONAL, DELIBERATELY INSECURE SAMPLE. See the README at the sample root. Never copy.
package com.example.cornerdeli.pos;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/** Corner Deli's checkout screen. */
public class PaymentActivity extends Activity {
    private static final String TAG = "CornerDeliPay";

    private final PaymentSdk paymentSdk = PaymentSdk.get();
    private final TransactionStore store = new TransactionStore(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payment);
        findViewById(R.id.pay_button).setOnClickListener(v -> pay(store.cartTotalCents()));
    }

    private void pay(long amountCents) {
        store.savePending(amountCents);
        paymentSdk.startPayment(amountCents, result -> {
            String cardNumber = result.maskedCardNumber();
            Log.d(TAG, "payment result for card " + cardNumber);
            store.saveResult(result.approved(), amountCents);
        });
    }
}

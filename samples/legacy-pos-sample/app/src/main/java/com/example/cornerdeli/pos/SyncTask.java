// FICTIONAL, DELIBERATELY INSECURE SAMPLE. See the README at the sample root. Never copy.
package com.example.cornerdeli.pos;

import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;

/** Uploads the day's sales over plain HTTP. */
public class SyncTask extends AsyncTask<Void, Void, Boolean> {
    private static final String ENDPOINT = "http://pos-backend.example.com/api/sales";

    private final ProgressDialog dialog;

    public SyncTask(Context context) {
        dialog = new ProgressDialog(context);
    }

    @Override
    protected Boolean doInBackground(Void... voids) {
        return HttpUtil.post(ENDPOINT, "{}");
    }
}

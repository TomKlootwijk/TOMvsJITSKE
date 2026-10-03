package nl.privaterecorder;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

/** App-private completion target for AppAuth's explicit PendingIntent. */
public final class OAuthRedirectActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView status = new TextView(this);
        status.setPadding(32, 48, 32, 32);
        status.setText("Finishing OneDrive sign-in…");
        setContentView(status);
        // Do not start a second code exchange when Android recreates this activity.
        // The consumed attempt remains in private storage until its first exchange completes.
        CloudAuth.finishMicrosoft(this, getIntent(), (provider, failure) -> {
            Intent settings = new Intent(this, CloudSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(CloudAuth.EXTRA_AUTH_PROVIDER, provider);
            if (failure != null) settings.putExtra(CloudAuth.EXTRA_AUTH_ERROR, failure.getMessage());
            startActivity(settings);
            finish();
        });
    }
}

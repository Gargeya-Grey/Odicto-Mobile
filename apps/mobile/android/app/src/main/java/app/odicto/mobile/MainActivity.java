package app.odicto.mobile;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import app.odicto.mobile.plugins.OnboardingPlugin;
import app.odicto.mobile.plugins.VoicePlugin;
import android.content.Intent;

public class MainActivity extends BridgeActivity {
    @Override public void onCreate(Bundle savedInstanceState) {
        registerPlugin(OnboardingPlugin.class);
        registerPlugin(VoicePlugin.class);
        super.onCreate(savedInstanceState);
        // Settings and History each own a web history entry. System back returns through them
        // to Home; only Home lets back close the app. Capacitor does not do this by itself.
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                android.webkit.WebView web = getBridge() != null ? getBridge().getWebView() : null;
                if (web != null && web.canGoBack()) {
                    web.goBack();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("voice_settings", false) && getBridge() != null) {
            getBridge().getWebView().evaluateJavascript("window.dispatchEvent(new Event('odicto-settings'))", null);
        }
    }
}

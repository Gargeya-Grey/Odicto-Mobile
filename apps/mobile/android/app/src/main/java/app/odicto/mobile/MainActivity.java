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
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("voice_settings", false) && getBridge() != null) {
            getBridge().getWebView().evaluateJavascript("window.dispatchEvent(new Event('odicto-settings'))", null);
        }
    }
}

package app.odicto.mobile;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewStructure;
import com.getcapacitor.CapacitorWebView;

/** Settings store API keys explicitly and do not expose login fields to autofill services. */
public class SettingsWebView extends CapacitorWebView {
    public SettingsWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override public int getAutofillType() {
        return AUTOFILL_TYPE_NONE;
    }

    @Override public void onProvideAutofillVirtualStructure(ViewStructure structure, int flags) {
        // WebView otherwise exposes virtual fields even when HTML autocomplete is off.
    }
}

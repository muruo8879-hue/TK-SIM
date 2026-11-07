package local.codex.tiktokgooglefix;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class LoginHook implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam app) {
        if (!"com.zhiliaoapp.musically".equals(app.packageName)) return;
        try {
            XposedHelpers.findAndHookMethod(
                    "com.bytedance.lobby.google.GoogleAuth", app.classLoader,
                    "isAvailable", XC_MethodReplacement.returnConstant(false));
            Log.i("TikTokWebLoginFix", "GoogleAuth.isAvailable hook installed; use the app's web fallback");
        } catch (Throwable failure) {
            Log.e("TikTokWebLoginFix", "Hook unavailable: " + failure.getClass().getSimpleName());
        }
        // Read-only verification after all embedded modules have initialized.
        new Handler(Looper.getMainLooper()).postDelayed(LoginHook::verifySimVisibility, 1500L);
    }

    private static void verifySimVisibility() {
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app == null) {
                Log.w("TikTokSIMReadback", "No application context; verification incomplete");
                return;
            }
            TelephonyManager phone = app.getSystemService(TelephonyManager.class);
            SubscriptionManager subscriptions = app.getSystemService(SubscriptionManager.class);
            String country = phone.getSimCountryIso();
            String operator = phone.getSimOperator();
            String name = phone.getSimOperatorName();
            String networkCountry = phone.getNetworkCountryIso();
            String networkOperator = phone.getNetworkOperator();
            java.util.List<?> list = subscriptions.getActiveSubscriptionInfoList();
            boolean hidden = phone.getSimState() == TelephonyManager.SIM_STATE_ABSENT
                    && phone.getSimState(0) == TelephonyManager.SIM_STATE_ABSENT
                    && phone.getSimState(1) == TelephonyManager.SIM_STATE_ABSENT
                    && !phone.hasIccCard() && "".equals(country) && "".equals(operator)
                    && "".equals(name) && "".equals(networkCountry)
                    && "".equals(networkOperator)
                    && subscriptions.getActiveSubscriptionInfoCount() == 0
                    && (list == null || list.isEmpty());
            Log.i("TikTokSIMReadback", "SIM API visibility check completed; hidden=" + hidden);
        } catch (Throwable failure) {
            Log.e("TikTokSIMReadback", "Verification incomplete: " + failure.getClass().getSimpleName());
        }
    }
}

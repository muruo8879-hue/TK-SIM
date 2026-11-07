package local.codex.tiktokmanager;

import android.content.*;
import android.content.pm.PackageInstaller;

public final class InstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent action = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            if (action != null) {
                action.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(action);
            }
            return;
        }
        String message = status == PackageInstaller.STATUS_SUCCESS ? "安装成功 · TikTok 已使用当前模块"
                : "系统返回：" + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    + "\n若系统安装页面已显示成功，返回本页面后会核验实际结果。";
        context.getSharedPreferences("state", 0).edit().putString("last", message).apply();
        Intent refresh = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(refresh);
    }
}

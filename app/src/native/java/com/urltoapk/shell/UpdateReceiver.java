package com.urltoapk.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** PackageInstaller's verdict on an update Updater committed. */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        if (!Updater.ACTION_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        long version = intent.getLongExtra(Updater.EXTRA_VERSION, 0);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) Updater.askUser(context, confirm);
                break;
            case PackageInstaller.STATUS_SUCCESS:
                // The new version replaces this process; it tidies the download on its first check.
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                // The user said no (or dismissed it): ask again on a later check.
                break;
            default:
                Updater.onFailed(context, version,
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }
}

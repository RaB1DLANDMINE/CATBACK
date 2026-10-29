package org.petal.catback;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

// bt acl still hits manifest receivers, so car connects -> start, leaves -> stop soon.
// presence service does the same on 13+, both are fine to double fire
public final class CarConnectionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null || !CarLink.isHeadUnit(context, device)) return;
        try {
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) CarLink.connect(context, device.getAddress());
            else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(intent.getAction())) CarLink.disconnected(context, device.getAddress());
        } catch (RuntimeException e) { Log.w("Catback", "Could not reach the projection service from background", e); }
    }
}
